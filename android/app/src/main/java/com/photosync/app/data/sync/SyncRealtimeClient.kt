package com.photosync.app.data.sync

import android.content.Context
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.repository.SyncRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Phase 4 实时通知客户端：连接服务端 WebSocket（GET /api/v1/ws?token=...），
 * 订阅本设备所属空间；收到 {type:'revision'} 提示后触发一次全量同步调度。
 *
 * 服务端只推送 revision 提示，不推事件数据——客户端仍靠 changes 接口拉取，
 * 保证事件顺序与幂等。WS 断连自动指数退避重连；未登录时保持空闲。
 */
class SyncRealtimeClient(
    private val context: Context,
    private val apiClient: ApiClient,
    private val repository: SyncRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private var job: Job? = null

    /** 实时提示去抖任务：连续到达的 revision 提示合并成一次同步。 */
    private var pendingSyncJob: Job? = null

    @Volatile
    private var active = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // 长连接不读超时
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun start() {
        if (active) return
        active = true
        job = scope.launch { runLoop() }
    }

    fun stop() {
        active = false
        job?.cancel()
        job = null
        pendingSyncJob?.cancel()
        pendingSyncJob = null
    }

    /**
     * 去抖后再排同步：一批上传会连续产生多条 revision 提示，
     * 若每条都排一次完整同步，会积压上百个任务并让目录状态一直闪。
     * 这里只保留最后一次提示，静默 [REALTIME_DEBOUNCE_MS] 后触发一次同步。
     *
     * 实时提示只代表远端有新变更，因此跳过本地扫描，避免无谓地翻转目录状态。
     */
    private fun requestSync() {
        pendingSyncJob?.cancel()
        pendingSyncJob = scope.launch {
            delay(REALTIME_DEBOUNCE_MS)
            SyncScheduler.enqueue(context, skipLocalScan = true)
        }
    }

    private suspend fun runLoop() {
        var backoff = 1000L
        while (active) {
            val deviceId = repository.currentDeviceId()
            val token = apiClient.accessToken()
            if (deviceId.isNullOrBlank() || token.isNullOrBlank()) {
                delay(5000)
                continue
            }
            val closedNormally = connect(deviceId, token)
            backoff = if (closedNormally) 1000L else (backoff * 2).coerceAtMost(60_000L)
            if (active) delay(backoff)
        }
    }

    private suspend fun connect(deviceId: String, token: String): Boolean {
        val base = apiClient.serverBaseUrl().trimEnd('/')
        val wsUrl = base
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") +
            "/api/v1/ws?token=" + token
        val closed = CompletableDeferred<Boolean>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    for (spaceId in repository.spaceIds()) {
                        webSocket.send(
                            json.encodeToString(
                                WsCommand.serializer(),
                                WsCommand(type = "subscribe", spaceId = spaceId, deviceId = deviceId)
                            )
                        )
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val hint = runCatching { json.decodeFromString(RevisionHint.serializer(), text) }.getOrNull()
                if (hint != null && hint.type == "revision" && hint.spaceId.isNotBlank()) {
                    requestSync()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(true)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(false)
            }
        }
        val socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        return try {
            closed.await()
        } finally {
            runCatching { socket.close(1000, "reconnect") }
        }
    }

    @Serializable
    private data class WsCommand(
        val type: String,
        val spaceId: String,
        val deviceId: String
    )

    @Serializable
    private data class RevisionHint(
        val type: String,
        val spaceId: String,
        val revision: String? = null
    )

    private companion object {
        /** 实时提示去抖窗口：窗口内的多条提示只触发一次同步。 */
        const val REALTIME_DEBOUNCE_MS = 2_000L
    }
}
