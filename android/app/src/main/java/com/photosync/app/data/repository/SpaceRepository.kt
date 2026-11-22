package com.photosync.app.data.repository

import androidx.room.withTransaction
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.local.MappingFileCount
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.local.SyncDatabase
import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.MappingCreateRequest
import com.photosync.app.data.remote.MappingDto
import com.photosync.app.data.remote.MappingUpdateRequest
import com.photosync.app.data.remote.MemberAddRequest
import com.photosync.app.data.remote.ScanManifestFile
import com.photosync.app.data.remote.ScanManifestRequest
import com.photosync.app.data.remote.ScanSummary
import com.photosync.app.data.remote.SpaceCreateRequest
import com.photosync.app.data.remote.SpaceDto
import com.photosync.app.data.remote.SpaceMemberDto
import com.photosync.app.data.remote.SpaceUpdateRequest
import com.photosync.app.data.remote.apiCall
import com.photosync.app.data.sync.FolderAccessException
import com.photosync.app.data.sync.FolderScanProgress
import com.photosync.app.data.sync.FolderScanner
import com.photosync.app.data.sync.RemotePrefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant

data class ScanPreview(
    val summary: ScanSummary,
    val totalLocalCount: Int,
    val truncated: Boolean
)

/** 同步空间、成员、目录映射和本地扫描的统一入口。 */
class SpaceRepository(
    private val apiClient: ApiClient,
    private val tokenStore: SecureTokenStore,
    private val database: SyncDatabase,
    private val folderScanner: FolderScanner
) {
    private val spaceDao = database.localSpaceDao()
    private val mappingDao = database.localFolderMappingDao()
    private val fileDao = database.localFileIndexDao()
    private val syncTaskDao = database.localSyncTaskDao()
    private val syncCursorDao = database.localSyncCursorDao()

    val spaces: Flow<List<LocalSpace>> = spaceDao.observeAll()

    fun observeSpace(spaceId: String): Flow<LocalSpace?> = spaceDao.observeById(spaceId)

    fun observeMappings(spaceId: String): Flow<List<LocalFolderMapping>> =
        mappingDao.observeBySpaceAndDevice(spaceId, currentDeviceId().orEmpty())

    fun observeMappingsForCurrentDevice(): Flow<List<LocalFolderMapping>> =
        mappingDao.observeByDevice(currentDeviceId().orEmpty())

    fun observeMapping(mappingId: String): Flow<LocalFolderMapping?> = mappingDao.observeById(mappingId)

    fun observeFiles(mappingId: String): Flow<List<LocalFileIndex>> = fileDao.observeByMapping(mappingId)

    fun observeFileCounts(spaceId: String): Flow<List<MappingFileCount>> =
        mappingDao.observeFileCounts(spaceId, currentDeviceId().orEmpty())

    fun currentDeviceId(): String? = tokenStore.currentDeviceId()

    suspend fun getSpace(spaceId: String): LocalSpace? = spaceDao.getById(spaceId)

    suspend fun refreshSpaces(): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.getSpaces() }) {
            is ApiResult.Success -> {
                val remoteSpaces = result.data.spaces
                database.withTransaction {
                    val remoteIds = remoteSpaces.map { it.id }.toSet()
                    spaceDao.getAll().filter { it.id !in remoteIds }.forEach { stale ->
                        mappingDao.getBySpace(stale.id).forEach { fileDao.clearByMapping(it.id) }
                        syncTaskDao.deleteBySpace(stale.id)
                        syncCursorDao.deleteBySpace(stale.id)
                        mappingDao.deleteBySpaceId(stale.id)
                        spaceDao.deleteById(stale.id)
                    }
                    spaceDao.upsertAll(remoteSpaces.map { it.toLocal(spaceDao.getById(it.id)) })
                }
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun createSpace(name: String, description: String?): ApiResult<LocalSpace> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        return when (
            val result = apiCall {
                apiClient.api.createSpace(SpaceCreateRequest(name, description, deviceId))
            }
        ) {
            is ApiResult.Success -> {
                val space = result.data.space.toLocal()
                spaceDao.upsertAll(listOf(space))
                ApiResult.Success(space)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun updateSpace(spaceId: String, name: String, description: String?): ApiResult<Unit> {
        return when (
            val result = apiCall {
                apiClient.api.updateSpace(spaceId, SpaceUpdateRequest(name, description))
            }
        ) {
            is ApiResult.Success -> {
                spaceDao.upsertAll(listOf(result.data.space.toLocal(spaceDao.getById(spaceId))))
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun deleteSpace(spaceId: String): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.deleteSpace(spaceId) }) {
            is ApiResult.Success -> {
                database.withTransaction {
                    mappingDao.getBySpace(spaceId).forEach { fileDao.clearByMapping(it.id) }
                    syncTaskDao.deleteBySpace(spaceId)
                    syncCursorDao.deleteBySpace(spaceId)
                    mappingDao.deleteBySpaceId(spaceId)
                    spaceDao.deleteById(spaceId)
                }
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun refreshSpace(spaceId: String): ApiResult<List<SpaceMemberDto>> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        val detail = when (val result = apiCall { apiClient.api.getSpace(spaceId) }) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return result
        }
        spaceDao.upsertAll(listOf(detail.space.toLocal(spaceDao.getById(spaceId))))

        when (val result = apiCall { apiClient.api.getMappings(spaceId, deviceId) }) {
            is ApiResult.Success -> {
                database.withTransaction {
                    val remoteIds = result.data.mappings.map { it.id }.toSet()
                    mappingDao.getBySpaceAndDevice(spaceId, deviceId)
                        .filter { it.id !in remoteIds }
                        .forEach { stale ->
                            fileDao.clearByMapping(stale.id)
                            syncTaskDao.deleteByMapping(stale.id)
                            mappingDao.deleteById(stale.id)
                        }
                    result.data.mappings.forEach { remote ->
                        mappingDao.upsert(remote.toLocal(mappingDao.getById(remote.id)))
                    }
                }
            }
            is ApiResult.Failure -> return result
        }
        return ApiResult.Success(detail.members)
    }

    suspend fun addMember(spaceId: String, deviceId: String): ApiResult<Unit> {
        return when (
            val result = apiCall {
                apiClient.api.addSpaceMember(spaceId, MemberAddRequest(deviceId))
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }

    suspend fun removeMember(spaceId: String, deviceId: String): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.removeSpaceMember(spaceId, deviceId) }) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }

    suspend fun createMapping(
        spaceId: String,
        localPathLabel: String,
        treeUri: String,
        remotePrefix: String,
        includeSubdirs: Boolean,
        onlyMedia: Boolean
    ): ApiResult<LocalFolderMapping> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        val validation = RemotePrefix.validate(remotePrefix)
        validation.error?.let { return ApiResult.Failure("VALIDATION_ERROR", it) }
        val normalized = validation.normalized
        val existing = mappingDao.getBySpaceAndDevice(spaceId, deviceId)
        if (existing.any { RemotePrefix.overlaps(it.remotePrefix, normalized) }) {
            return ApiResult.Failure("MAPPING_OVERLAP", "该路径与同空间的本机已有映射重叠")
        }

        val membership = ensureCurrentDeviceIsMember(spaceId, deviceId)
        if (membership is ApiResult.Failure) return membership

        return when (
            val result = apiCall {
                apiClient.api.createMapping(
                    spaceId,
                    MappingCreateRequest(
                        deviceId = deviceId,
                        localPathLabel = localPathLabel,
                        remotePrefix = normalized,
                        includeSubdirs = includeSubdirs,
                        onlyMedia = onlyMedia
                    )
                )
            }
        ) {
            is ApiResult.Success -> {
                val mapping = result.data.mapping.toLocal(null).copy(
                    treeUri = treeUri,
                    status = MappingStatus.ACTIVE
                )
                mappingDao.upsert(mapping)
                spaceDao.adjustMappingCount(spaceId, 1)
                ApiResult.Success(mapping)
            }
            is ApiResult.Failure -> result
        }
    }

    private suspend fun ensureCurrentDeviceIsMember(spaceId: String, deviceId: String): ApiResult<Unit> {
        val detail = when (val result = apiCall { apiClient.api.getSpace(spaceId) }) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return result
        }
        spaceDao.upsertAll(listOf(detail.space.toLocal(spaceDao.getById(spaceId))))
        if (detail.members.any { it.deviceId == deviceId }) return ApiResult.Success(Unit)
        return addMember(spaceId, deviceId)
    }

    suspend fun updateMapping(
        mapping: LocalFolderMapping,
        remotePrefix: String,
        includeSubdirs: Boolean,
        onlyMedia: Boolean
    ): ApiResult<Unit> {
        val validation = RemotePrefix.validate(remotePrefix)
        validation.error?.let { return ApiResult.Failure("VALIDATION_ERROR", it) }
        val normalized = validation.normalized
        val siblings = mappingDao.getBySpaceAndDevice(mapping.spaceId, mapping.deviceId)
        if (siblings.any { it.id != mapping.id && RemotePrefix.overlaps(it.remotePrefix, normalized) }) {
            return ApiResult.Failure("MAPPING_OVERLAP", "该路径与同空间的本机已有映射重叠")
        }

        return when (
            val result = apiCall {
                apiClient.api.updateMapping(
                    mapping.id,
                    MappingUpdateRequest(
                        localPathLabel = mapping.localPathLabel,
                        remotePrefix = normalized,
                        includeSubdirs = includeSubdirs,
                        onlyMedia = onlyMedia
                    )
                )
            }
        ) {
            is ApiResult.Success -> {
                mappingDao.upsert(
                    result.data.mapping.toLocal(mapping).copy(treeUri = mapping.treeUri)
                )
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun deleteMapping(mappingId: String): ApiResult<Unit> {
        val local = mappingDao.getById(mappingId)
        return when (val result = apiCall { apiClient.api.deleteMapping(mappingId) }) {
            is ApiResult.Success -> {
                database.withTransaction {
                    fileDao.clearByMapping(mappingId)
                    syncTaskDao.deleteByMapping(mappingId)
                    mappingDao.deleteById(mappingId)
                    local?.let { spaceDao.adjustMappingCount(it.spaceId, -1) }
                }
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /**
     * 只有完整遍历成功才替换索引。授权或读取失败仅更新映射状态，绝不清空旧索引。
     * 同步引擎与差异预览共用这一本地扫描过程，失败扫描绝不会被当作删除。
     */
    suspend fun scanMappingLocally(
        mappingId: String,
        onProgress: (FolderScanProgress) -> Unit = {}
    ): ApiResult<List<LocalFileIndex>> {
        val original = mappingDao.getById(mappingId)
            ?: return ApiResult.Failure("MAPPING_NOT_FOUND", "本地目录映射不存在")
        mappingDao.upsert(original.copy(status = MappingStatus.SCANNING, lastScanComplete = false))

        val files = try {
            folderScanner.scan(original, onProgress)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { mappingDao.upsert(original) }
            throw e
        } catch (e: FolderAccessException) {
            mappingDao.upsert(
                original.copy(status = MappingStatus.ACCESS_REVOKED, lastScanComplete = false)
            )
            return ApiResult.Failure("ACCESS_REVOKED", e.message ?: "本地目录授权已失效")
        } catch (e: Exception) {
            mappingDao.upsert(original.copy(status = MappingStatus.ERROR, lastScanComplete = false))
            return ApiResult.Failure("SCAN_ERROR", e.message ?: "扫描本地目录失败")
        }

        val scannedAt = Instant.now().toString()
        database.withTransaction {
            fileDao.replaceAfterCompleteScan(mappingId, files)
            mappingDao.upsert(
                original.copy(
                    status = MappingStatus.ACTIVE,
                    lastScanComplete = true,
                    lastScannedAt = scannedAt
                )
            )
        }
        return ApiResult.Success(files)
    }

    suspend fun scanMapping(
        mappingId: String,
        onProgress: (FolderScanProgress) -> Unit = {}
    ): ApiResult<ScanPreview> {
        val original = mappingDao.getById(mappingId)
            ?: return ApiResult.Failure("MAPPING_NOT_FOUND", "本地目录映射不存在")
        val files = when (val result = scanMappingLocally(mappingId, onProgress)) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return result
        }

        val truncated = files.size > MAX_MANIFEST_FILES
        val manifestFiles = files.take(MAX_MANIFEST_FILES).map { file ->
            ScanManifestFile(
                relativePath = file.relativePath,
                size = file.size,
                sha256 = file.sha256,
                modifiedAt = if (file.modifiedAt > 0L) {
                    Instant.ofEpochMilli(file.modifiedAt).toString()
                } else {
                    null
                }
            )
        }
        return when (
            val result = apiCall {
                apiClient.api.scanManifest(
                    original.spaceId,
                    ScanManifestRequest(
                        deviceId = original.deviceId,
                        mappingId = mappingId,
                        // 截断清单不能让服务端误判为完整差异，也不能据此生成删除。
                        scanComplete = !truncated,
                        files = manifestFiles
                    )
                )
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(
                ScanPreview(
                    summary = result.data.summary,
                    totalLocalCount = files.size,
                    truncated = truncated
                )
            )
            is ApiResult.Failure -> result
        }
    }

    suspend fun clearCache() {
        database.withTransaction {
            syncTaskDao.clear()
            syncCursorDao.clear()
            fileDao.clear()
            mappingDao.clear()
            spaceDao.clear()
        }
    }

    private fun SpaceDto.toLocal(existing: LocalSpace? = null) = LocalSpace(
        id = id,
        name = name,
        description = description,
        ownerId = ownerId,
        role = role,
        revision = revision,
        memberCount = memberCount,
        mappingCount = mappingCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSyncedAt = existing?.lastSyncedAt
    )

    private fun MappingDto.toLocal(existing: LocalFolderMapping?) = LocalFolderMapping(
        id = id,
        spaceId = spaceId,
        deviceId = deviceId,
        localPathLabel = localPathLabel,
        treeUri = existing?.treeUri.orEmpty(),
        remotePrefix = remotePrefix,
        includeSubdirs = includeSubdirs,
        onlyMedia = onlyMedia,
        status = existing?.status
            ?: if (existing?.treeUri.isNullOrEmpty()) MappingStatus.ACCESS_REVOKED else MappingStatus.ACTIVE,
        lastScanComplete = existing?.lastScanComplete ?: false,
        lastScannedAt = existing?.lastScannedAt,
        createdAt = createdAt ?: existing?.createdAt
    )

    private companion object {
        const val MAX_MANIFEST_FILES = 10_000
    }
}
