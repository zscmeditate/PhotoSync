package com.photosync.app.data.remote

import com.photosync.app.data.prefs.SecureTokenStore
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 把请求从占位 Base URL 动态改写为用户配置的服务器地址，
 * 使 Retrofit 实例无需在切换服务器时重建。
 */
class BaseUrlInterceptor(private val baseUrlProvider: () -> String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val baseUrl = baseUrlProvider()?.toHttpUrlOrNull()
            ?: return chain.proceed(chain.request())
        val original = chain.request()
        val rewritten = original.url.newBuilder()
            .scheme(baseUrl.scheme)
            .host(baseUrl.host)
            .port(baseUrl.port)
            .encodedPath(joinPaths(baseUrl.encodedPath, original.url.encodedPath))
            .build()
        return chain.proceed(original.newBuilder().url(rewritten).build())
    }

    private fun joinPaths(base: String, path: String): String {
        val prefix = base.trimEnd('/')
        return if (prefix.isEmpty() || prefix == "/") path else prefix + path
    }
}

/**
 * 自动附加 `Authorization: Bearer <accessToken>`；
 * 收到 401 时用 refreshToken 调 /auth/refresh 轮换 Token 并重放请求一次。
 */
class AuthInterceptor(
    private val tokenStore: SecureTokenStore,
    private val authApi: ApiService,
    private val json: Json
) : Interceptor {

    private val refreshLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val accessToken = tokenStore.accessToken
        val request = if (accessToken.isNullOrBlank()) {
            original
        } else {
            original.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        }

        val response = chain.proceed(request)
        if (response.code != 401 || accessToken.isNullOrBlank()) return response
        if (original.header(HEADER_RETRIED) != null) return response
        if (original.url.encodedPath.contains("/auth/login") ||
            original.url.encodedPath.contains("/auth/register")
        ) {
            return response
        }

        response.close()
        val newToken = synchronized(refreshLock) {
            if (tokenStore.accessToken != accessToken) {
                tokenStore.accessToken
            } else {
                performRefresh()
            }
        } ?: return chain.proceed(request.newBuilder().header(HEADER_RETRIED, "1").build())

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .header(HEADER_RETRIED, "1")
                .build()
        )
    }

    /** 返回刷新后的 accessToken；失败时清空会话并返回 null。 */
    private fun performRefresh(): String? {
        val refreshToken = tokenStore.refreshToken ?: return null
        return try {
            val response = authApi.refreshBlocking(RefreshRequest(refreshToken))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                tokenStore.saveTokens(body.accessToken, body.refreshToken)
                body.accessToken
            } else {
                tokenStore.clear()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val HEADER_RETRIED = "X-PhotoSync-Auth-Retry"
    }
}
