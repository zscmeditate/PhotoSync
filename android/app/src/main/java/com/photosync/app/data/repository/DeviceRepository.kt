package com.photosync.app.data.repository

import android.os.Build
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.local.LocalDeviceDao
import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.DeviceDto
import com.photosync.app.data.remote.DeviceRegisterRequest
import com.photosync.app.data.remote.DeviceRenameRequest
import com.photosync.app.data.remote.apiCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant

/** 设备管理：列表（Room 缓存）、重命名、登记本机。 */
class DeviceRepository(
    private val apiClient: ApiClient,
    private val tokenStore: SecureTokenStore,
    private val localDeviceDao: LocalDeviceDao
) {

    val devices: Flow<List<LocalDevice>> = localDeviceDao.observeAll()

    /** 本机是否为当前账号的主设备（决定能否创建空间等管理操作） */
    val isThisDevicePrimary: Flow<Boolean> = localDeviceDao.observeAll().map { list ->
        list.any { device -> device.isThisDevice && device.isPrimary }
    }

    suspend fun refreshDevices(): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.getDevices() }) {
            is ApiResult.Success -> {
                localDeviceDao.replaceAll(result.data.devices.map { it.toLocal() })
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

    /** 将本机设为主设备；账号已有主设备时服务端返回冲突。 */
    suspend fun claimPrimaryDevice(): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.claimPrimaryDevice() }) {
            is ApiResult.Success -> {
                localDeviceDao.upsertAll(listOf(result.data.device.toLocal()))
                refreshDevices()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** 本机放弃主设备身份，之后其它设备可以重新抢占。 */
    suspend fun releasePrimaryDevice(): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.releasePrimaryDevice() }) {
            is ApiResult.Success -> {
                refreshDevices()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** 由主设备移除从设备（吊销其凭据）。 */
    suspend fun removeDevice(deviceId: String): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.deleteDevice(deviceId) }) {
            is ApiResult.Success -> {
                refreshDevices()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** 主设备把已移除设备的记录从列表中清理（重新申请时会再次出现）。 */
    suspend fun purgeRemovedDevice(deviceId: String): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.purgeRemovedDevice(deviceId) }) {
            is ApiResult.Success -> {
                refreshDevices()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** 被移除的本机申请重新加入，需等待主设备同意。 */
    suspend fun requestRejoin(): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.requestRejoinDevice() }) {
            is ApiResult.Success -> {
                localDeviceDao.upsertAll(listOf(result.data.device.toLocal()))
                refreshDevices()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** 主设备同意被移除设备重新加入。 */
    suspend fun approveRejoin(deviceId: String): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.approveDeviceRejoin(deviceId) }) {
            is ApiResult.Success -> {
                localDeviceDao.upsertAll(listOf(result.data.device.toLocal()))
                refreshDevices()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** 主设备拒绝被移除设备的重新加入申请。 */
    suspend fun rejectRejoin(deviceId: String): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.rejectDeviceRejoin(deviceId) }) {
            is ApiResult.Success -> {
                refreshDevices()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /**
     * 确保本机在服务端有一条设备记录：
     * 带上本机已有的 deviceId，服务端原地复用，因此刷新/重登不会新增重复设备。
     * 已被主设备移除的设备重新登记后仍为 revoked，需要申请并由主设备同意。
     */
    suspend fun ensureThisDevice(deviceName: String): ApiResult<String> {
        val name = deviceName.ifBlank { Build.MODEL }
        // 硬件标识（Widevine）刚启动时可能瞬时读取失败，稍等重试；
        // 不做兜底：仍读不到就直接失败，避免服务端分配随机 ID 导致重装后出现重复设备
        var requestedId = tokenStore.localDeviceId
        var waited = 0
        while (requestedId == null && waited < 5) {
            delay(200)
            requestedId = tokenStore.localDeviceId
            waited++
        }
        val deviceId = requestedId ?: return ApiResult.Failure(
            code = "DEVICE_ID_UNAVAILABLE",
            message = "无法读取设备硬件标识（当前机型不支持 Widevine），无法登记设备"
        )
        return when (val result = apiCall {
            apiClient.api.registerDevice(
                DeviceRegisterRequest(name, deviceId)
            )
        }) {
            is ApiResult.Success -> {
                val device = result.data.device
                tokenStore.localDeviceId = device.id
                localDeviceDao.upsertAll(listOf(device.toLocal()))
                ApiResult.Success(device.id)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun clearCache() {
        localDeviceDao.clear()
    }

    private fun DeviceDto.toLocal(): LocalDevice {
        val localId = tokenStore.localDeviceId
        val thisDevice = localId != null && localId == id
        return LocalDevice(
            id = id,
            deviceName = deviceName,
            platform = platform,
            credentialStatus = credentialStatus,
            isPrimary = isPrimary,
            isRejoinRequested = isRejoinRequested,
            isThisDevice = thisDevice,
            // 本机正在运行 App 即为在线，不依赖服务端心跳时间，避免时钟偏差/心跳延迟造成误显示离线
            online = if (thisDevice) true else isRecentlySeen(lastSeenAt),
            createdAt = createdAt,
            lastSeenAt = lastSeenAt
        )
    }

    companion object {
        /** 契约中的设备对象没有 online 字段，按 lastSeenAt 最近 5 分钟判定在线；容忍服务器时钟偏快（负偏差）。 */
        fun isRecentlySeen(lastSeenAt: String?, withinMinutes: Long = 5): Boolean {
            if (lastSeenAt.isNullOrBlank()) return false
            return try {
                val seen = Instant.parse(lastSeenAt)
                val diff = Duration.between(seen, Instant.now())
                diff.toMinutes() < withinMinutes
            } catch (e: Exception) {
                false
            }
        }
    }
}
