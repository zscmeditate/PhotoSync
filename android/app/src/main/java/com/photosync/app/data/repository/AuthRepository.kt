package com.photosync.app.data.repository

import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.DeviceRegisterRequest
import com.photosync.app.data.remote.LoginRequest
import com.photosync.app.data.remote.LogoutRequest
import com.photosync.app.data.remote.RegisterRequest
import com.photosync.app.data.remote.apiCall

const val DEFAULT_DEVICE_NAME = "我的 Android 手机"

/** 登录/注册/刷新/退出 + 登录成功后自动注册本机设备。 */
class AuthRepository(
    private val apiClient: ApiClient,
    private val tokenStore: SecureTokenStore
) {

    suspend fun login(email: String, password: String): ApiResult<Unit> {
        val result = apiCall { apiClient.api.login(LoginRequest(email, password)) }
        return when (result) {
            is ApiResult.Success -> {
                val auth = result.data
                tokenStore.saveSession(
                    userId = auth.user.id,
                    email = auth.user.email,
                    displayName = auth.user.displayName,
                    access = auth.accessToken,
                    refresh = auth.refreshToken
                )
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun register(email: String, password: String, displayName: String): ApiResult<Unit> {
        val result = apiCall {
            apiClient.api.register(RegisterRequest(email, password, displayName))
        }
        return when (result) {
            is ApiResult.Success -> {
                val auth = result.data
                tokenStore.saveSession(
                    userId = auth.user.id,
                    email = auth.user.email,
                    displayName = auth.user.displayName,
                    access = auth.accessToken,
                    refresh = auth.refreshToken
                )
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** 登录/注册成功后注册本机设备，保存本机 deviceId。 */
    suspend fun registerThisDevice(deviceName: String): ApiResult<String> {
        val name = deviceName.ifBlank { DEFAULT_DEVICE_NAME }
        return when (val result = apiCall {
            apiClient.api.registerDevice(DeviceRegisterRequest(name))
        }) {
            is ApiResult.Success -> {
                val device = result.data.device
                tokenStore.localDeviceId = device.id
                ApiResult.Success(device.id)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun logout(): ApiResult<Unit> {
        val refreshToken = tokenStore.refreshToken
        if (refreshToken != null) {
            // 尽力调用服务端注销；失败也继续清理本地会话
            apiCall { apiClient.api.logout(LogoutRequest(refreshToken)) }
        }
        tokenStore.clear()
        return ApiResult.Success(Unit)
    }
}
