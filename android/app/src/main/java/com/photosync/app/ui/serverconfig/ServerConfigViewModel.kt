package com.photosync.app.ui.serverconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.repository.ServerRepository
import com.photosync.app.util.formatIsoTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 健康检查结果（连接状态 / API 版本 / 服务端时间 / 延迟） */
data class HealthUiState(
    val testing: Boolean = false,
    val tested: Boolean = false,
    val connected: Boolean = false,
    val statusText: String = "尚未测试",
    val apiVersion: String = "-",
    val serverTime: String = "-",
    val latencyText: String = "-"
)

sealed interface ServerConfigState {
    data object Idle : ServerConfigState
    data object Saved : ServerConfigState
    data class Error(val message: String) : ServerConfigState
}

class ServerConfigViewModel(
    private val serverRepository: ServerRepository,
    private val tokenStore: SecureTokenStore
) : ViewModel() {

    private val _health = MutableStateFlow(HealthUiState())
    val health: StateFlow<HealthUiState> = _health.asStateFlow()

    private val _state = MutableStateFlow<ServerConfigState>(ServerConfigState.Idle)
    val state: StateFlow<ServerConfigState> = _state.asStateFlow()

    fun validate(url: String, allowHttp: Boolean): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return "请输入服务器地址"
        if (trimmed.contains("@")) return "URL 中不允许包含账号或密码"
        val normalized = trimmed.trimEnd('/')
        val httpUrl = "$normalized/".toHttpUrlOrNull() ?: return "服务器地址格式不正确"
        return when {
            httpUrl.scheme == "https" -> null
            httpUrl.scheme == "http" && allowHttp -> null
            httpUrl.scheme == "http" -> "HTTP 明文连接不安全，请勾选「允许 HTTP（仅开发环境）」"
            else -> "仅支持 http:// 或 https:// 地址"
        }
    }

    fun testConnection(url: String, allowHttp: Boolean) {
        val error = validate(url, allowHttp)
        if (error != null) {
            _health.value = HealthUiState(tested = true, connected = false, statusText = "连接失败：$error")
            return
        }
        viewModelScope.launch {
            _health.value = HealthUiState(testing = true, statusText = "正在测试…")
            val start = System.currentTimeMillis()
            val result = serverRepository.testConnection(url.trim().trimEnd('/'))
            val latency = System.currentTimeMillis() - start
            _health.value = when (result) {
                is ApiResult.Success -> HealthUiState(
                    tested = true,
                    connected = true,
                    statusText = "连接成功（${result.data.status}）",
                    apiVersion = result.data.apiVersion,
                    serverTime = formatIsoTime(result.data.serverTime),
                    latencyText = "$latency ms"
                )
                is ApiResult.Failure -> HealthUiState(
                    tested = true,
                    connected = false,
                    statusText = "连接失败：${result.message}"
                )
            }
        }
    }

    fun save(url: String, allowHttp: Boolean) {
        val error = validate(url, allowHttp)
        if (error != null) {
            _state.value = ServerConfigState.Error(error)
            return
        }
        viewModelScope.launch {
            serverRepository.saveServerConfig(url, allowHttp)
            _state.value = ServerConfigState.Saved
        }
    }

    fun consumeState() {
        _state.value = ServerConfigState.Idle
    }

    fun isLoggedIn(): Boolean = tokenStore.isLoggedIn
}
