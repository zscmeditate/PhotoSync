package com.photosync.app.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.photosync.app.MainActivity
import com.photosync.app.R
import com.photosync.app.data.local.SyncTaskStats
import com.photosync.app.data.local.SyncTaskType
import com.photosync.app.data.repository.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 同步进度通知（同时充当 WorkManager 的前台服务通知）。
 *
 * 通知里展示「当前正在传输的空间」：左侧空间名称，右侧上下两行上传/下载进度。
 * 同步引擎用全局互斥锁串行执行，同一时刻只有一个空间在真正传输，
 * 因此固定用一条通知、内容随空间切换，避免切换通知 ID 时前台通知残留。
 *
 * 数据来源两部分：
 * - 任务表统计 [SyncTaskStats]：剩余任务数与剩余大小（含执行中的任务）；
 * - 引擎的会话完成量 [SpaceTaskCompletion]：本次同步已完成的任务、大小与正在传输的字节数。
 * 两者相加即「总数」，因此新规划出的任务会让总数增长，而不是凭空出现。
 */
class SyncProgressNotifier(
    context: Context,
    private val repository: SyncRepository,
    private val engine: SyncEngine
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 有 SyncWorker 正在执行：前台服务通知由 WorkManager 收尾，这里不主动撤销。 */
    private val workerRunning = AtomicBoolean(false)

    /** 同步工作是否在进行（含扫描、传输等所有阶段），供首页等界面展示「同步中」。 */
    private val _workerActive = MutableStateFlow(false)
    val workerActive: StateFlow<Boolean> = _workerActive.asStateFlow()

    /** 最近一次发布的进度通知，SyncWorker 用它把同步提升为前台服务。 */
    private val _foreground = MutableStateFlow<Notification?>(null)
    val foreground: StateFlow<Notification?> = _foreground.asStateFlow()

    /** 通知内容版本号：撤销通知前校验，避免删掉新一轮同步刚发布的通知。 */
    private val generation = AtomicInteger(0)

    private var lastPostedAt = 0L
    private var lastPostedKey = ""

    fun start() {
        ensureChannel()
        launchCollector()
    }

    fun onWorkerStarted() {
        workerRunning.set(true)
        _workerActive.value = true
    }

    fun onWorkerFinished() {
        workerRunning.set(false)
        _workerActive.value = false
        scheduleCancel()
    }

    /** 包装成前台服务通知；Android 10+ 需要声明 dataSync 类型。 */
    fun asForegroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }

    /** 系统要求前台通知但当前没有进度（例如正在扫描目录）时的兜底通知。 */
    fun idleForegroundInfo(): ForegroundInfo = asForegroundInfo(buildIdleNotification())

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launchCollector() {
        scope.launch {
            engine.taskProgress
                // 串行同步下最多一个空间在传输；取最近开始的会话
                .map { sessions -> sessions.maxByOrNull { it.value.startedAt }?.key }
                .distinctUntilChanged()
                .flatMapLatest { spaceId -> progressFlow(spaceId) }
                .collect { content ->
                    if (content == null) {
                        scheduleCancel()
                        return@collect
                    }
                    val now = SystemClock.elapsedRealtime()
                    val key = "${content.upload.doneTasks}/${content.upload.totalTasks}/" +
                        "${content.download.doneTasks}/${content.download.totalTasks}"
                    val tasksChanged = key != lastPostedKey
                    // 仅字节数变化时降频（系统对通知刷新频率有上限），任务数变化时尽快刷新
                    val minInterval =
                        if (tasksChanged) MIN_POST_INTERVAL_MS else MIN_BYTE_UPDATE_INTERVAL_MS
                    if (now - lastPostedAt < minInterval) return@collect
                    lastPostedAt = now
                    lastPostedKey = key
                    post(content)
                }
        }
    }

    private fun progressFlow(spaceId: String?): Flow<ProgressContent?> = flow {
        if (spaceId == null) {
            emit(null)
            return@flow
        }
        val spaceName = repository.spaceName(spaceId)?.takeIf { it.isNotBlank() } ?: "空间"
        emitAll(
            combine(repository.observeStats(spaceId), engine.taskProgress) { stats, sessions ->
                sessions[spaceId]?.let { session -> buildContent(spaceName, stats, session) }
            }
        )
    }

    private fun buildContent(
        spaceName: String,
        stats: SyncTaskStats,
        session: SpaceTaskCompletion
    ): ProgressContent? {
        val upload = transferLine(
            doneTasks = session.uploadTasks,
            doneBytes = session.uploadBytes + session.activeUploadBytes,
            remainingTasks = stats.pendingUploads,
            // 任务表统计含执行中任务的完整大小，要扣掉已传部分才是真正的剩余大小
            remainingBytes = (stats.pendingUploadBytes - session.activeUploadBytes).coerceAtLeast(0L)
        )
        val download = transferLine(
            doneTasks = session.downloadTasks,
            doneBytes = session.downloadBytes + session.activeDownloadBytes,
            remainingTasks = stats.pendingDownloads,
            remainingBytes = (stats.pendingDownloadBytes - session.activeDownloadBytes)
                .coerceAtLeast(0L)
        )
        // 没有要展示的任务（空跑一轮同步）时不出通知
        if (upload.totalTasks + download.totalTasks == 0) return null
        return ProgressContent(spaceName, upload, download, session.activeType)
    }

    private fun post(content: ProgressContent) {
        // 只显示当前在做的那一侧：上传中就只显示上传，下载中就只显示下载；
        // 任务切换的间隙才两行都显示
        val active = activeLine(content)
        val lines = if (active != null) {
            listOf(active.text)
        } else {
            listOf(formatLine("上传", content.upload), formatLine("下载", content.download))
        }
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        // 用系统标准通知模板（标题 + 文本 + 进度条），与其它 App 的下载类通知保持一致的观感
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setColor(ContextCompat.getColor(appContext, R.color.brand_blue))
            .setContentTitle(content.spaceName)
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
        // 进度条跟随当前显示的范围：只显示一侧时用该侧的进度，两行都显示时用合计
        val scope = active?.line ?: combined(content)
        if (scope.totalBytes > 0) {
            builder.setProgress(
                1000,
                (scope.doneBytes * 1000 / scope.totalBytes).toInt().coerceIn(0, 1000),
                false
            )
        }
        val notification = builder.build()
        generation.incrementAndGet()
        manager.notify(NOTIFICATION_ID, notification)
        _foreground.value = notification
    }

    /** 同步结束后稍等一下再撤销：期间若有新一轮同步发布通知，会因版本号变化而跳过撤销。 */
    private fun scheduleCancel() {
        // 还在前台服务里：留着让 WorkManager 在任务结束时收尾，避免摘掉前台通知
        if (workerRunning.get()) return
        val scheduled = generation.incrementAndGet()
        scope.launch {
            delay(CANCEL_DELAY_MS)
            if (scheduled == generation.get()) {
                manager.cancel(NOTIFICATION_ID)
                _foreground.value = null
            }
        }
    }

    private fun buildIdleNotification(): Notification =
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setContentTitle("正在同步")
            .setContentText("正在检查本地与云端文件…")
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setProgress(0, 0, true)
            .build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "同步进度",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示正在进行的文件上传与下载进度"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 已完成 / 总数（已传大小 / 总大小）。 */
    private data class TransferLine(
        val doneTasks: Int,
        val totalTasks: Int,
        val doneBytes: Long,
        val totalBytes: Long
    )

    private data class ProgressContent(
        val spaceName: String,
        val upload: TransferLine,
        val download: TransferLine,
        /** 当前正在传输的方向；null 表示此刻没有任务在传。 */
        val activeType: String?
    )

    private fun transferLine(
        doneTasks: Int,
        doneBytes: Long,
        remainingTasks: Int,
        remainingBytes: Long
    ) = TransferLine(
        doneTasks = doneTasks,
        totalTasks = doneTasks + remainingTasks,
        doneBytes = doneBytes,
        totalBytes = doneBytes + remainingBytes
    )

    /**
     * 当前该显示哪一侧：
     * 1. 正在上传 / 正在下载 → 只显示该方向；
     * 2. 任务切换间隙（没有在传的任务）→ 若只有一侧还有任务就显示那一侧；
     * 3. 两侧都有任务且都空闲 → 返回 null，由调用方两行都显示。
     */
    private fun activeLine(content: ProgressContent): ActiveLine? {
        return when (content.activeType) {
            SyncTaskType.UPLOAD -> ActiveLine(formatLine("上传", content.upload), content.upload)
            SyncTaskType.DOWNLOAD -> ActiveLine(formatLine("下载", content.download), content.download)
            else -> when {
                content.upload.totalTasks > 0 && content.download.totalTasks == 0 ->
                    ActiveLine(formatLine("上传", content.upload), content.upload)
                content.download.totalTasks > 0 && content.upload.totalTasks == 0 ->
                    ActiveLine(formatLine("下载", content.download), content.download)
                else -> null
            }
        }
    }

    private fun combined(content: ProgressContent) = TransferLine(
        doneTasks = content.upload.doneTasks + content.download.doneTasks,
        totalTasks = content.upload.totalTasks + content.download.totalTasks,
        doneBytes = content.upload.doneBytes + content.download.doneBytes,
        totalBytes = content.upload.totalBytes + content.download.totalBytes
    )

    private data class ActiveLine(val text: String, val line: TransferLine)

    /** 一行展示：上传/下载：完成 / 总数 · 已传大小 / 总大小。 */
    private fun formatLine(label: String, line: TransferLine): String =
        "$label：${line.doneTasks} / ${line.totalTasks}" +
            " · ${formatSize(line.doneBytes)} / ${formatSize(line.totalBytes)}"

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0.0 MB"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) {
            String.format(Locale.US, "%.1f GB", mb / 1024)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "photosync_sync_progress"
        const val MIN_POST_INTERVAL_MS = 300L
        const val MIN_BYTE_UPDATE_INTERVAL_MS = 800L
        const val CANCEL_DELAY_MS = 1200L
    }
}
