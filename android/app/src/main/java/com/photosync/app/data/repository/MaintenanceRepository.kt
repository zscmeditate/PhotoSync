package com.photosync.app.data.repository

import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.ConflictDto
import com.photosync.app.data.remote.RemoteFileVersionDto
import com.photosync.app.data.remote.ResolveConflictRequest
import com.photosync.app.data.remote.ResolveConflictResponse
import com.photosync.app.data.remote.RestoreTrashRequest
import com.photosync.app.data.remote.TrashItemDto
import com.photosync.app.data.remote.apiCall
import java.util.UUID

/** 回收站、冲突与历史版本的 API 入口（Phase 5 服务端能力，Phase 6 客户端接入）。 */
class MaintenanceRepository(
    apiClient: ApiClient,
    private val tokenStore: SecureTokenStore
) {
    private val api = apiClient.api

    suspend fun trash(spaceId: String): ApiResult<List<TrashItemDto>> {
        val deviceId = deviceIdOrNull() ?: return missingDevice()
        return when (val result = apiCall { api.getTrash(spaceId, deviceId) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.items)
            is ApiResult.Failure -> result
        }
    }

    suspend fun restore(trashId: String): ApiResult<Unit> {
        val deviceId = deviceIdOrNull() ?: return missingDevice()
        return when (
            val result = apiCall {
                api.restoreTrash(trashId, RestoreTrashRequest(deviceId, UUID.randomUUID().toString()))
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }

    suspend fun purge(trashId: String): ApiResult<Unit> {
        val deviceId = deviceIdOrNull() ?: return missingDevice()
        return when (val result = apiCall { api.purgeTrash(trashId, deviceId) }) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }

    suspend fun conflicts(spaceId: String): ApiResult<List<ConflictDto>> {
        val deviceId = deviceIdOrNull() ?: return missingDevice()
        return when (val result = apiCall { api.getConflicts(spaceId, deviceId) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.conflicts)
            is ApiResult.Failure -> result
        }
    }

    suspend fun resolveConflict(
        conflictId: String,
        resolution: String
    ): ApiResult<ResolveConflictResponse> {
        val deviceId = deviceIdOrNull() ?: return missingDevice()
        val body = ResolveConflictRequest(
            deviceId = deviceId,
            operationId = UUID.randomUUID().toString(),
            resolution = resolution
        )
        return apiCall { api.resolveConflict(conflictId, body) }
    }

    suspend fun versions(fileId: String): ApiResult<List<RemoteFileVersionDto>> {
        val deviceId = deviceIdOrNull() ?: return missingDevice()
        return when (val result = apiCall { api.getFileVersions(fileId, deviceId) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.versions)
            is ApiResult.Failure -> result
        }
    }

    private fun deviceIdOrNull(): String? = tokenStore.currentDeviceId()?.takeIf { it.isNotBlank() }

    private fun <T> missingDevice(): ApiResult<T> =
        ApiResult.Failure("DEVICE_NOT_REGISTERED", "本机设备尚未注册，请重新登录")
}
