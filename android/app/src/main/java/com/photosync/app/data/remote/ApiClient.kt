package com.photosync.app.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 构建 Retrofit / OkHttp 客户端。
 * Base URL 动态改写（BaseUrlInterceptor），鉴权自动附带 Token 并支持 401 刷新重放。
 */
class ApiClient(
    private val settingsRepository: SettingsRepository,
    private val tokenStore: SecureTokenStore
) {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val cachedBaseUrl = AtomicReference<String?>(null)

    private fun currentBaseUrl(): String {
        cachedBaseUrl.get()?.let { return it }
        val url = runBlocking { settingsRepository.getServerBaseUrl() }
        cachedBaseUrl.compareAndSet(null, url)
        return cachedBaseUrl.get() ?: url
    }

    fun updateBaseUrl(url: String) {
        cachedBaseUrl.set(url.trim().trimEnd('/'))
    }

    private val baseUrlInterceptor = BaseUrlInterceptor { currentBaseUrl() }

    /** 无鉴权客户端：login/register/refresh 使用 */
    private val plainClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(baseUrlInterceptor)
        .build()

    private val authApi: ApiService by lazy { buildApi(plainClient) }

    /** 鉴权客户端：自动 Bearer + 401 刷新重放 */
    private val authedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(baseUrlInterceptor)
        .addInterceptor(AuthInterceptor(tokenStore, authApi, json))
        .build()

    val api: ApiService by lazy { buildApi(authedClient) }

    /** WebSocket 客户端使用：当前服务端 base 地址（http/https，由调用方改写成 ws/wss）。 */
    fun serverBaseUrl(): String = currentBaseUrl()

    /** WebSocket 客户端使用：当前 access token（query 参数透传，WS 握手不走 AuthInterceptor）。 */
    fun accessToken(): String? = tokenStore.accessToken

    private fun buildApi(client: OkHttpClient): ApiService {
        return Retrofit.Builder()
            // 占位地址，实际地址由 BaseUrlInterceptor 改写
            .baseUrl("https://photosync.invalid/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }
}
