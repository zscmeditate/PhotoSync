package com.photosync.app.data.repository

import com.photosync.app.data.prefs.SettingsRepository
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.HealthResponse
import com.photosync.app.data.remote.apiCall

/** 服务器配置与健康检查。 */
class ServerRepository(
    private val apiClient: ApiClient,
    private val settingsRepository: SettingsRepository
) {

    val serverBaseUrl = settingsRepository.serverBaseUrl
    val allowHttp = settingsRepository.allowHttp

    suspend fun getServerBaseUrl(): String = settingsRepository.getServerBaseUrl()

    suspend fun saveServerConfig(url: String, allowHttpEnabled: Boolean) {
        val normalized = normalizeBaseUrl(url)
        settingsRepository.saveServerConfig(normalized, allowHttpEnabled)
        apiClient.updateBaseUrl(normalized)
    }

    /** 测试健康检查接口；可传入尚未保存的地址用于预检（仅更新内存中的 Base URL）。 */
    suspend fun testConnection(baseUrl: String? = null): ApiResult<HealthResponse> {
        if (baseUrl != null) {
            apiClient.updateBaseUrl(baseUrl)
        }
        return apiCall { apiClient.api.health() }
    }

    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

    fun isHttp(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)
}
