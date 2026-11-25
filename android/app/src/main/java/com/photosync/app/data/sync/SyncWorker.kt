package com.photosync.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.prefs.SettingsRepository
import java.util.concurrent.TimeUnit

/** Room 任务与 WorkManager 请求均可跨进程重启恢复。 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? PhotoSyncApp ?: return Result.failure()
        if (app.container.syncRepository.currentDeviceId().isNullOrBlank()) return Result.failure()
        val preferences = app.container.settingsRepository.getSyncPreferences()
        if (preferences.wifiOnly && !hasWifiOrEthernet(applicationContext)) {
            // 仅 Wi-Fi 模式：等待网络条件满足后由 WorkManager 重试，不消耗蜂窝流量。
            return Result.retry()
        }
        // 传入 spaceId 时只同步该空间（空间详情页手动同步的后台兜底）；
        // 未传入时走全量同步（应用启动、周期同步、实时变更提示等全局触发）。
        val spaceId = inputData.getString(KEY_SPACE_ID)
        val engine = app.container.syncEngine
        return when (val result = if (spaceId.isNullOrBlank()) engine.syncAll() else engine.syncSpace(spaceId)) {
            SyncRunResult.SUCCESS -> Result.success()
            SyncRunResult.RETRY -> Result.retry()
            SyncRunResult.FAILURE -> Result.failure()
        }
    }

    companion object {
        /** 输入参数：非空时 SyncWorker 只同步该空间，为空/null 时全量同步。 */
        const val KEY_SPACE_ID = "spaceId"
    }

    private fun hasWifiOrEthernet(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

object SyncScheduler {
    private const val ONCE_WORK_NAME = "photosync-connected-sync"
    private const val PERIODIC_WORK_NAME = "photosync-periodic-sync"

    /**
     * 立即同步：实时提示、手动同步使用。Wi-Fi 限制在 SyncWorker 内检查。
     * @param force 手动同步为 true：取消可能卡在退避重试中的旧任务并立即执行，
     *              避免 ExistingWorkPolicy.KEEP 丢弃用户点击；后台触发保持追加，不打断正在运行的同步。
     * @param spaceId 非空时只同步该空间（空间内手动操作的兜底）；为 null 时全量同步所有空间。
     */
    fun enqueue(context: Context, force: Boolean = false, spaceId: String? = null) {
        val inputData = Data.Builder().apply {
            if (!spaceId.isNullOrBlank()) putString(SyncWorker.KEY_SPACE_ID, spaceId)
        }.build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .setConstraints(connectedConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // 带 spaceId 时按空间隔离唯一任务名，避免不同空间的手动同步互相顶替；
        // 不带 spaceId 的全局触发（启动/周期/实时提示）共用基础名。
        val workName = if (spaceId.isNullOrBlank()) ONCE_WORK_NAME else "$ONCE_WORK_NAME:$spaceId"
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    /** 根据设置重建后台周期同步；关闭自动同步时移除周期任务。 */
    suspend fun reschedule(context: Context) {
        val preferences = SettingsRepository(context.applicationContext).getSyncPreferences()
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!preferences.autoSyncEnabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val interval = preferences.intervalMinutes
            .coerceAtLeast(com.photosync.app.data.prefs.SyncPreferences.MIN_INTERVAL_MINUTES)
            .toLong()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(connectedConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(ONCE_WORK_NAME)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    private fun connectedConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
}
