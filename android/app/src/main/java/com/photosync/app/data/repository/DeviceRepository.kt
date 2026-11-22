package com.photosync.app.data.repository

import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.local.LocalDeviceDao
import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.DeviceDto
import com.photosync.app.data.remote.DeviceRegisterRequest
import com.photosync.app.data.remote.DeviceRenameRequest
import com.photosync.app.data.remote.apiCall
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant

/** 设备管理：列表（Room 缓存）、重命名、移除、注册本机。 */
class DeviceRepository(
    private val apiClient: ApiClient,
    private val tokenStore: SecureTokenStore,
    private val localDeviceDao: LocalDeviceDao
) {

    val devices: Flow<List<LocalDevice>> = localDeviceDao.observeAll()

    suspend fun refreshDevices(): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.getDevices() }) {
            is ApiResult.Success -> {
                localDeviceDao.clear()
                localDeviceDao.upsertAll(result.data.devices.map { it.toLocal() })
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun renameDevice(deviceId: String, newName: String): ApiResult<Unit> {
        return when (val result = apiCall {
            apiClient.api.renameDevice(deviceId, DeviceRenameRequest(newName))
        }) {
            is ApiResult.Success -> {
                localDeviceDao.upsertAll(listOf(result.data.device.toLocal()))
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun removeDevice(deviceId: String): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.deleteDevice(deviceId) }) {
            is ApiResult.Success -> {
                localDeviceDao.deleteById(deviceId)
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** "添加新设备"= 把本机再次注册到服务端。 */
    suspend fun registerThisDevice(deviceName: String): ApiResult<Unit> {
        val name = deviceName.ifBlank { DEFAULT_DEVICE_NAME }
        return when (val result = apiCall {
            apiClient.api.registerDevice(DeviceRegisterRequest(name))
        }) {
            is ApiResult.Success -> {
                val device = result.data.device
                tokenStore.localDeviceId = device.id
                localDeviceDao.upsertAll(listOf(device.toLocal()))
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun clearCache() {
        localDeviceDao.clear()
    }

    private fun DeviceDto.toLocal(): LocalDevice {
        val localId = tokenStore.localDeviceId
        return LocalDevice(
            id = id,
            deviceName = deviceName,
            platform = platform,
            credentialStatus = credentialStatus,
            isThisDevice = localId != null && localId == id,
            online = isRecentlySeen(lastSeenAt),
            createdAt = createdAt,
            lastSeenAt = lastSeenAt
        )
    }

    companion object {
        /** 契约中的设备对象没有 online 字段，按 lastSeenAt 最近 5 分钟判定在线。 */
        fun isRecentlySeen(lastSeenAt: String?, withinMinutes: Long = 5): Boolean {
            if (lastSeenAt.isNullOrBlank()) return false
            return try {
                val seen = Instant.parse(lastSeenAt)
                val diff = Duration.between(seen, Instant.now())
                !diff.isNegative && diff.toMinutes() < withinMinutes
            } catch (e: Exception) {
                false
            }
        }
    }
}
