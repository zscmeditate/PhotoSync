package com.photosync.app.data.repository

import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.local.MappingFileCount
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.local.SpaceCoverStore
import com.photosync.app.data.local.SyncDatabase
import com.photosync.app.data.prefs.PendingBinding
import com.photosync.app.data.prefs.PendingBindingStore
import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.JoinRequestDto
import com.photosync.app.data.remote.JoinRequestSubmitResponse
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

data class ScanPreview(
    val summary: ScanSummary,
    val totalLocalCount: Int,
    val truncated: Boolean
)

/** 「绑定目录」的结果：已是成员则直接建立映射，否则先提交加入申请并暂存目录。 */
sealed interface BindOutcome {
    data class Bound(val mapping: LocalFolderMapping) : BindOutcome
    data object JoinPending : BindOutcome
}

/** 同步空间、成员、目录映射和本地扫描的统一入口。 */
class SpaceRepository(
    private val apiClient: ApiClient,
    private val tokenStore: SecureTokenStore,
    private val database: SyncDatabase,
    private val folderScanner: FolderScanner,
    private val pendingBindingStore: PendingBindingStore,
    private val spaceCoverStore: SpaceCoverStore
) {
    private val spaceDao = database.localSpaceDao()
    private val mappingDao = database.localFolderMappingDao()
    private val fileDao = database.localFileIndexDao()
    private val syncTaskDao = database.localSyncTaskDao()
    private val syncCursorDao = database.localSyncCursorDao()
    private val pendingDeletionDao = database.localPendingDeletionDao()

    /** 全部空间：包含本机尚未加入（role=none）的空间，同步空间列表用于展示并申请加入 */
    val allSpaces: Flow<List<LocalSpace>> = spaceDao.observeAll()

    /** 首页只展示本机已加入的空间 */
    val spaces: Flow<List<LocalSpace>> = allSpaces
        .map { list -> list.filter { it.role != "none" } }

    fun observeSpace(spaceId: String): Flow<LocalSpace?> = spaceDao.observeById(spaceId)

    fun observeMappings(spaceId: String): Flow<List<LocalFolderMapping>> =
        mappingDao.observeBySpaceAndDevice(spaceId, currentDeviceId().orEmpty())

    fun observeMappingsForCurrentDevice(): Flow<List<LocalFolderMapping>> =
        mappingDao.observeByDevice(currentDeviceId().orEmpty())

    fun observeMapping(mappingId: String): Flow<LocalFolderMapping?> = mappingDao.observeById(mappingId)

    fun observeFiles(mappingId: String): Flow<List<LocalFileIndex>> = fileDao.observeByMapping(mappingId)

    fun observeFileCounts(spaceId: String): Flow<List<MappingFileCount>> =
        mappingDao.observeFileCounts(spaceId, currentDeviceId().orEmpty())

    /** 本机各空间的文件总数（按本机目录映射的本地索引统计），用于空间列表名称后展示。 */
    fun observeSpaceFileCounts(): Flow<Map<String, Int>> =
        mappingDao.observeFileCountsBySpace(currentDeviceId().orEmpty())
            .map { counts -> counts.associate { it.spaceId to it.fileCount } }

    fun currentDeviceId(): String? = tokenStore.currentDeviceId()

    suspend fun getSpace(spaceId: String): LocalSpace? = spaceDao.getById(spaceId)

    /**
     * 管理员为该空间设置头像：先在客户端压缩，再上传到服务端，
     * 成功后立即用返回的版本号缓存到本机（其他设备下次刷新空间列表时自动拉取）。
     */
    suspend fun uploadSpaceCover(spaceId: String, uri: Uri): ApiResult<Unit> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        val bytes = spaceCoverStore.compressFromUri(uri)
            ?: return ApiResult.Failure("IMAGE_READ_FAILED", "照片读取失败，请换一张试试")
        return when (
            val result = apiCall {
                apiClient.api.uploadSpaceCover(
                    spaceId,
                    deviceId,
                    "image/jpeg",
                    bytes.toRequestBody("image/jpeg".toMediaType())
                )
            }
        ) {
            is ApiResult.Success -> {
                val dto = result.data.space
                spaceDao.upsertAll(listOf(dto.toLocal(spaceDao.getById(spaceId))))
                dto.coverUpdatedAt?.let { version ->
                    spaceCoverStore.saveBytes(spaceId, version, bytes)?.let { path ->
                        spaceDao.updateCover(spaceId, path, version)
                    }
                }
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** 管理员移除空间头像；清掉本机缓存，其他设备刷新后恢复默认图标。 */
    suspend fun removeSpaceCover(spaceId: String): ApiResult<Unit> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        return when (val result = apiCall { apiClient.api.deleteSpaceCover(spaceId, deviceId) }) {
            is ApiResult.Success -> {
                spaceDao.upsertAll(
                    listOf(result.data.space.toLocal(spaceDao.getById(spaceId)))
                )
                spaceDao.updateCover(spaceId, null, null)
                spaceCoverStore.delete(spaceId)
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /**
     * 按服务端版本号同步各空间头像：
     * 有新版本才下载并缓存，管理员清除头像后同步清掉本机文件与展示。
     */
    private suspend fun syncSpaceCovers(remoteSpaces: List<SpaceDto>) {
        val deviceId = currentDeviceId()
        remoteSpaces.forEach { dto ->
            val version = dto.coverUpdatedAt
            if (version == null) {
                if (spaceDao.getById(dto.id)?.coverPath != null) {
                    spaceDao.updateCover(dto.id, null, null)
                }
                spaceCoverStore.delete(dto.id)
                return@forEach
            }
            val local = spaceDao.getById(dto.id)
            if (local?.coverPath != null && spaceCoverStore.isCached(dto.id, version)) {
                return@forEach
            }
            Log.d(TAG, "syncCover ${dto.id.take(8)} version=$version localPath=${local?.coverPath}")
            val bytes = withContext(Dispatchers.IO) {
                try {
                    val response = apiClient.api.getSpaceCover(dto.id, deviceId)
                    Log.d(TAG, "syncCover ${dto.id.take(8)} http=${response.code()}")
                    if (!response.isSuccessful) {
                        null
                    } else {
                        response.body()?.use { body ->
                            body.bytes().takeIf { it.isNotEmpty() && it.size <= MAX_COVER_BYTES }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "syncCover ${dto.id.take(8)} 下载失败", e)
                    null
                }
            }
            if (bytes == null) return@forEach
            val path = spaceCoverStore.saveBytes(dto.id, version, bytes) ?: return@forEach
            Log.d(TAG, "syncCover ${dto.id.take(8)} 已缓存 ${bytes.size} bytes")
            spaceDao.updateCover(dto.id, path, version)
        }
    }

    suspend fun getAllSpaces(): List<LocalSpace> = spaceDao.getAll()

    /** 只读查询某台设备在空间内是否已有目录映射，用于「加入空间」时过滤已加入的空间。 */
    suspend fun hasDeviceMapping(spaceId: String, deviceId: String): ApiResult<Boolean> {
        return when (val result = apiCall { apiClient.api.getMappings(spaceId, deviceId) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.mappings.isNotEmpty())
            is ApiResult.Failure -> result
        }
    }

    suspend fun refreshSpaces(): ApiResult<Unit> {
        return when (val result = apiCall { apiClient.api.getSpaces(currentDeviceId()) }) {
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
                // 空间列表更新后尝试恢复本机在服务端记录的目录绑定（重装/重新登录场景）
                restoreDeviceMappings()
                // 拉取管理员设置的空间头像（按版本号增量下载）
                syncSpaceCovers(remoteSpaces)
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
                apiClient.api.updateSpace(
                    spaceId,
                    SpaceUpdateRequest(name, description, currentDeviceId())
                )
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
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        return when (val result = apiCall { apiClient.api.deleteSpace(spaceId, deviceId) }) {
            is ApiResult.Success -> {
                database.withTransaction {
                    mappingDao.getBySpace(spaceId).forEach { fileDao.clearByMapping(it.id) }
                    syncTaskDao.deleteBySpace(spaceId)
                    syncCursorDao.deleteBySpace(spaceId)
                    mappingDao.deleteBySpaceId(spaceId)
                    spaceDao.deleteById(spaceId)
                }
                spaceCoverStore.delete(spaceId)
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** 轻量加载空间成员：只拉空间详情，不拉目录映射（成员变更后刷新参与设备用）。 */
    suspend fun loadMembers(spaceId: String): ApiResult<List<SpaceMemberDto>> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        val detail = when (val result = apiCall { apiClient.api.getSpace(spaceId, deviceId) }) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return result
        }
        spaceDao.upsertAll(listOf(detail.space.toLocal(spaceDao.getById(spaceId))))
        return ApiResult.Success(detail.members)
    }

    suspend fun refreshSpace(spaceId: String): ApiResult<List<SpaceMemberDto>> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        val detail = when (val result = apiCall { apiClient.api.getSpace(spaceId, deviceId) }) {
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
                            pendingDeletionDao.deleteByMapping(stale.id)
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
        val operatorDeviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        return when (
            val result = apiCall {
                apiClient.api.removeSpaceMember(spaceId, deviceId, operatorDeviceId)
            }
        ) {
            is ApiResult.Success -> {
                // 本机退出空间：与服务端保持一致，整体清除本地映射、索引、任务与游标。
                // 否则重新加入时会带着旧游标，远端已有文件将永远无法补下载。
                if (deviceId == operatorDeviceId) {
                    database.withTransaction {
                        mappingDao.getBySpaceAndDevice(spaceId, deviceId).forEach { mapping ->
                            fileDao.clearByMapping(mapping.id)
                            mappingDao.deleteById(mapping.id)
                        }
                        syncTaskDao.deleteBySpace(spaceId)
                        syncCursorDao.deleteBySpace(spaceId)
                        pendingDeletionDao.deleteBySpace(spaceId)
                    }
                    pendingBindingStore.clear(spaceId)
                }
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /** 从本机申请加入空间；主设备申请时服务端直接通过。 */
    suspend fun requestJoinSpace(spaceId: String): ApiResult<JoinRequestSubmitResponse> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        return when (
            val result = apiCall {
                apiClient.api.requestJoinSpace(spaceId, MemberAddRequest(deviceId))
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(result.data)
            is ApiResult.Failure -> result
        }
    }

    /** 主设备读取待审批的加入申请。 */
    suspend fun loadJoinRequests(spaceId: String): ApiResult<List<JoinRequestDto>> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        return when (val result = apiCall { apiClient.api.getJoinRequests(spaceId, deviceId) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.requests)
            is ApiResult.Failure -> result
        }
    }

    /** 主设备同意或拒绝一条加入申请。 */
    suspend fun resolveJoinRequest(requestId: String, approve: Boolean): ApiResult<JoinRequestDto> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        return when (
            val result = apiCall {
                if (approve) {
                    apiClient.api.approveJoinRequest(requestId, MemberAddRequest(deviceId))
                } else {
                    apiClient.api.rejectJoinRequest(requestId, MemberAddRequest(deviceId))
                }
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(result.data.request)
            is ApiResult.Failure -> result
        }
    }

    /**
     * 重装或重新登录后，从服务端恢复本机记录的目录绑定：
     * 目录仍可访问则直接恢复为可用映射；无法访问（例如卸载后 SAF 授权丢失）先标记为待重新授权，
     * 由用户在空间页重新选择目录。本地已有的绑定不会被覆盖。
     */
    suspend fun restoreDeviceMappings(): ApiResult<Int> {
        val deviceId = currentDeviceId() ?: return ApiResult.Success(0)
        val remote = when (val result = apiCall { apiClient.api.getDeviceMappings(deviceId) }) {
            is ApiResult.Success -> result.data.mappings
            is ApiResult.Failure -> return result
        }
        var restored = 0
        database.withTransaction {
            remote.forEach { dto ->
                val treeUri = dto.treeUri
                if (treeUri.isNullOrBlank()) return@forEach
                if (spaceDao.getById(dto.spaceId) == null) return@forEach
                if (mappingDao.getBySpaceAndDevice(dto.spaceId, deviceId).isNotEmpty()) return@forEach
                val accessible = folderScanner.canAccessTree(treeUri)
                mappingDao.upsert(
                    LocalFolderMapping(
                        id = dto.id,
                        spaceId = dto.spaceId,
                        deviceId = deviceId,
                        localPathLabel = dto.localPathLabel,
                        treeUri = treeUri,
                        onlyMedia = dto.onlyMedia,
                        status = if (accessible) MappingStatus.ACTIVE else MappingStatus.ACCESS_REVOKED,
                        lastScanComplete = false,
                        lastScannedAt = null,
                        createdAt = dto.createdAt
                    )
                )
                if (accessible) restored++
            }
        }
        return ApiResult.Success(restored)
    }

    suspend fun createMapping(
        spaceId: String,
        localPathLabel: String,
        treeUri: String,
        onlyMedia: Boolean
    ): ApiResult<LocalFolderMapping> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        val existing = mappingDao.getBySpaceAndDevice(spaceId, deviceId)
        if (existing.isNotEmpty()) {
            return ApiResult.Failure("MAPPING_EXISTS", "该空间已绑定本地目录，请先删除原目录映射")
        }
        directoryInUseFailure(deviceId, treeUri, spaceId)?.let { return it }

        val membership = ensureCurrentDeviceIsMember(spaceId, deviceId)
        if (membership is ApiResult.Failure) return membership

        return when (
            val result = apiCall {
                apiClient.api.createMapping(
                    spaceId,
                    MappingCreateRequest(
                        deviceId = deviceId,
                        localPathLabel = localPathLabel,
                        treeUri = treeUri,
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
        val detail = when (val result = apiCall { apiClient.api.getSpace(spaceId, deviceId) }) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return result
        }
        spaceDao.upsertAll(listOf(detail.space.toLocal(spaceDao.getById(spaceId))))
        if (detail.members.any { it.deviceId == deviceId }) return ApiResult.Success(Unit)
        // 绑定目录不能自动成为成员：必须由主设备同意加入申请。
        return ApiResult.Failure(
            "NOT_MEMBER",
            "本机尚未加入该空间，请先在「加入空间」中提交申请并等待主设备同意"
        )
    }

    /**
     * 同一个本地目录只能绑定一个空间：若本机其他空间已绑定该目录，返回失败结果。
     * 目录身份以 SAF 树 URI 为准，忽略末尾斜杠差异。
     */
    private suspend fun directoryInUseFailure(
        deviceId: String,
        treeUri: String,
        spaceId: String
    ): ApiResult.Failure? {
        val target = treeUri.trimEnd('/')
        val conflict = mappingDao.getByDevice(deviceId)
            .firstOrNull { it.spaceId != spaceId && it.treeUri.trimEnd('/') == target }
            ?: return null
        val conflictName = spaceDao.getById(conflict.spaceId)?.name ?: "其他空间"
        return ApiResult.Failure(
            "DIRECTORY_IN_USE",
            "该本地目录已绑定空间「$conflictName」，一个本地目录只能绑定一个空间"
        )
    }

    /** 本机在该空间是否有「已选目录、等待主设备同意」的暂存绑定。 */
    fun observePendingBinding(spaceId: String): Flow<Boolean> =
        pendingBindingStore.observe(spaceId).map { it != null }

    /**
     * 绑定目录与申请加入合并为一个动作：
     * 已是成员则直接建立映射；不是成员则提交加入申请并暂存本地目录，等待主设备同意。
     */
    suspend fun bindDirectory(
        spaceId: String,
        localPathLabel: String,
        treeUri: String,
        onlyMedia: Boolean
    ): ApiResult<BindOutcome> {
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        if (mappingDao.getBySpaceAndDevice(spaceId, deviceId).isNotEmpty()) {
            return ApiResult.Failure("MAPPING_EXISTS", "该空间已绑定本地目录")
        }
        directoryInUseFailure(deviceId, treeUri, spaceId)?.let { return it }

        val detail = when (val result = apiCall { apiClient.api.getSpace(spaceId, deviceId) }) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return result
        }
        spaceDao.upsertAll(listOf(detail.space.toLocal(spaceDao.getById(spaceId))))
        var member = detail.members.any { it.deviceId == deviceId }
        if (!member) {
            when (val join = requestJoinSpace(spaceId)) {
                is ApiResult.Failure -> {
                    if (join.code != "ALREADY_MEMBER") return join
                    member = true
                }
                is ApiResult.Success -> member = join.data.joined
            }
        }
        if (!member) {
            pendingBindingStore.save(
                PendingBinding(
                    spaceId = spaceId,
                    treeUri = treeUri,
                    localPathLabel = localPathLabel,
                    onlyMedia = onlyMedia
                )
            )
            return ApiResult.Success(BindOutcome.JoinPending)
        }
        return when (val result = createMapping(spaceId, localPathLabel, treeUri, onlyMedia)) {
            is ApiResult.Success -> ApiResult.Success(BindOutcome.Bound(result.data))
            is ApiResult.Failure -> result
        }
    }

    /** 主设备同意加入后，用暂存的目录自动完成映射；尚未通过审批时返回 null。 */
    suspend fun finishPendingBinding(spaceId: String): ApiResult<LocalFolderMapping?> {
        val pending = pendingBindingStore.get(spaceId) ?: return ApiResult.Success(null)
        val deviceId = currentDeviceId()
            ?: return ApiResult.Failure("INVALID_DEVICE", "本机设备尚未注册，请先在设备管理页注册")
        val detail = when (val result = apiCall { apiClient.api.getSpace(spaceId, deviceId) }) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return result
        }
        spaceDao.upsertAll(listOf(detail.space.toLocal(spaceDao.getById(spaceId))))
        if (detail.members.none { it.deviceId == deviceId }) {
            return ApiResult.Success(null)
        }
        return when (
            val result = createMapping(
                spaceId,
                pending.localPathLabel,
                pending.treeUri,
                pending.onlyMedia
            )
        ) {
            is ApiResult.Success -> {
                pendingBindingStore.clear(spaceId)
                ApiResult.Success(result.data)
            }
            is ApiResult.Failure -> when (result.code) {
                "MAPPING_EXISTS" -> {
                    pendingBindingStore.clear(spaceId)
                    ApiResult.Success(null)
                }
                // 暂存目录在等待审批期间已被其他空间占用，清理暂存并提示原因。
                "DIRECTORY_IN_USE" -> {
                    pendingBindingStore.clear(spaceId)
                    result
                }
                else -> result
            }
        }
    }

    /**
     * 更改本地目录绑定：同步服务端展示名，清空旧目录索引后用新目录重新建立基线。
     * 目录绑定不允许删除，只能替换，避免出现没有本地目录的空间。
     */
    suspend fun changeMappingDirectory(
        mapping: LocalFolderMapping,
        treeUri: String,
        localPathLabel: String
    ): ApiResult<Unit> {
        directoryInUseFailure(mapping.deviceId, treeUri, mapping.spaceId)?.let { return it }
        return when (
            val result = apiCall {
                apiClient.api.updateMapping(
                    mapping.id,
                    MappingUpdateRequest(
                        localPathLabel = localPathLabel,
                        treeUri = treeUri
                    )
                )
            }
        ) {
            is ApiResult.Success -> {
                database.withTransaction {
                    fileDao.clearByMapping(mapping.id)
                    syncTaskDao.deleteByMapping(mapping.id)
                    mappingDao.upsert(
                        mapping.copy(
                            treeUri = treeUri,
                            localPathLabel = localPathLabel,
                            status = MappingStatus.ACTIVE,
                            lastScanComplete = false,
                            lastScannedAt = null
                        )
                    )
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
        // 上次扫描被中断可能残留 SCANNING：恢复时不能把它写回，否则该映射会被 activeMappings 永久过滤。
        val restoreStatus = if (original.status == MappingStatus.SCANNING) {
            MappingStatus.ACTIVE
        } else {
            original.status
        }
        mappingDao.upsert(original.copy(status = MappingStatus.SCANNING, lastScanComplete = false))

        val files = try {
            folderScanner.scan(original, onProgress)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { mappingDao.upsert(original.copy(status = restoreStatus)) }
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
        try {
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
        } catch (e: CancellationException) {
            // 扫描已完成但写回索引时被取消：事务回滚，状态仍要还原，避免卡在“扫描中”。
            withContext(NonCancellable) { mappingDao.upsert(original.copy(status = restoreStatus)) }
            throw e
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
            syncTaskDao.clearAllTasks()
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
        lastSyncedAt = existing?.lastSyncedAt,
        coverPath = existing?.coverPath,
        coverVersion = coverUpdatedAt
    )

    private fun MappingDto.toLocal(existing: LocalFolderMapping?) = LocalFolderMapping(
        id = id,
        spaceId = spaceId,
        deviceId = deviceId,
        localPathLabel = localPathLabel,
        treeUri = existing?.treeUri.orEmpty(),
        onlyMedia = onlyMedia,
        status = existing?.status
            ?: if (existing?.treeUri.isNullOrEmpty()) MappingStatus.ACCESS_REVOKED else MappingStatus.ACTIVE,
        lastScanComplete = existing?.lastScanComplete ?: false,
        lastScannedAt = existing?.lastScannedAt,
        createdAt = createdAt ?: existing?.createdAt
    )

    private companion object {
        const val TAG = "SpaceRepository"
        const val MAX_MANIFEST_FILES = 10_000
        /** 服务端限制头像 3MB，客户端再留一点余量防止异常响应。 */
        const val MAX_COVER_BYTES = 4 * 1024 * 1024
    }
}
