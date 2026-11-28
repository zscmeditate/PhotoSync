package com.photosync.app.data.remote

import kotlinx.serialization.Serializable

/** 统一错误封装：{"error":{"code":"STRING","message":"string"}} */
@Serializable
data class ErrorEnvelope(val error: ApiErrorDetail? = null)

@Serializable
data class ApiErrorDetail(val code: String? = null, val message: String? = null)

// ---------- health ----------

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val apiVersion: String,
    val serverTime: String
)

// ---------- auth ----------

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val user: UserDto,
    val accessToken: String,
    val refreshToken: String
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String
)

@Serializable
data class LogoutRequest(val refreshToken: String)

// ---------- devices ----------

@Serializable
data class DeviceDto(
    val id: String,
    val userId: String,
    val deviceName: String,
    val platform: String,
    val credentialStatus: String,
    /** 是否为当前账号的主设备（同一账号最多一台） */
    val isPrimary: Boolean = false,
    /** 被移除设备是否正在等待主设备同意重新加入 */
    val isRejoinRequested: Boolean = false,
    val createdAt: String? = null,
    val lastSeenAt: String? = null
)

@Serializable
data class DeviceRegisterRequest(
    val deviceName: String,
    /** 本机已登记过的设备 ID：传入则服务端原地复用，不会新增设备 */
    val deviceId: String? = null
)

@Serializable
data class DeviceRenameRequest(val deviceName: String)

@Serializable
data class DeviceResponse(val device: DeviceDto)

@Serializable
data class DevicesResponse(val devices: List<DeviceDto>)

// ---------- spaces ----------

@Serializable
data class SpaceDto(
    val id: String,
    val ownerId: String,
    val name: String,
    val description: String? = null,
    val revision: String = "0",
    val role: String,
    /** 本机最近一次加入申请的状态；null 表示从未申请或接口未提供。 */
    val joinRequestStatus: String? = null,
    val memberCount: Int = 0,
    val mappingCount: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** 空间头像最近更新时间（ISO8601）；null 表示管理员未设置头像 */
    val coverUpdatedAt: String? = null
)

@Serializable
data class SpaceCreateRequest(
    val name: String,
    val description: String? = null,
    val deviceId: String
)

@Serializable
data class SpaceUpdateRequest(
    val name: String? = null,
    val description: String? = null,
    /** 操作者设备 ID：服务端据此校验是否为主设备 */
    val deviceId: String? = null
)

@Serializable
data class SpaceResponse(val space: SpaceDto)

@Serializable
data class SpacesResponse(val spaces: List<SpaceDto>)

@Serializable
data class SpaceMemberDto(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val role: String,
    val joinedAt: String? = null,
    val lastSeenAt: String? = null,
    val online: Boolean = false
)

@Serializable
data class SpaceDetailResponse(
    val space: SpaceDto,
    val members: List<SpaceMemberDto> = emptyList()
)

@Serializable
data class MemberAddRequest(val deviceId: String)

@Serializable
data class MemberResponse(val member: SpaceMemberDto)

@Serializable
data class JoinRequestDto(
    val id: String,
    val spaceId: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val status: String,
    val requestedAt: String? = null,
    val resolvedAt: String? = null
)

@Serializable
data class JoinRequestSubmitResponse(
    val request: JoinRequestDto,
    val joined: Boolean
)

@Serializable
data class JoinRequestListResponse(val requests: List<JoinRequestDto> = emptyList())

@Serializable
data class JoinRequestResponse(val request: JoinRequestDto)

// ---------- folder mappings ----------

@Serializable
data class MappingDto(
    val id: String,
    val spaceId: String,
    val deviceId: String,
    val localPathLabel: String,
    val treeUri: String? = null,
    val onlyMedia: Boolean,
    val createdAt: String? = null
)

@Serializable
data class MappingCreateRequest(
    val deviceId: String,
    val localPathLabel: String,
    val treeUri: String? = null,
    val onlyMedia: Boolean
)

@Serializable
data class MappingUpdateRequest(
    val localPathLabel: String? = null,
    val treeUri: String? = null
)

@Serializable
data class MappingResponse(val mapping: MappingDto)

@Serializable
data class MappingsResponse(val mappings: List<MappingDto>)

// ---------- scan manifest ----------

@Serializable
data class ScanManifestFile(
    val relativePath: String,
    val size: Long,
    val sha256: String? = null,
    val modifiedAt: String? = null
)

@Serializable
data class ScanManifestRequest(
    val deviceId: String,
    val mappingId: String,
    val scanComplete: Boolean,
    val files: List<ScanManifestFile>
)

@Serializable
data class ScanSummary(
    val localCount: Int = 0,
    val remoteCount: Int = 0,
    val toUpload: Int = 0,
    val toDownload: Int = 0,
    val conflictCount: Int = 0,
    val diffComplete: Boolean = true
)

@Serializable
data class RemoteFileDto(
    val fileId: String,
    val relativePath: String,
    val objectId: String? = null,
    val size: Long,
    val sha256: String? = null,
    val version: Int = 1
)

@Serializable
data class ScanManifestResponse(
    val summary: ScanSummary,
    val remoteFiles: List<RemoteFileDto> = emptyList()
)

// ---------- incremental sync ----------

@Serializable
data class UploadCreateRequest(
    val spaceId: String,
    val deviceId: String,
    val relativePath: String,
    val size: Long,
    val sha256: String,
    val partSize: Long,
    val idempotencyKey: String
)

@Serializable
data class UploadedPartDto(
    val partNumber: Int,
    val size: String,
    val sha256: String
)

@Serializable
data class UploadDto(
    val uploadId: String,
    val spaceId: String,
    val objectId: String,
    val relativePath: String,
    val size: String,
    val sha256: String,
    val partSize: String,
    val status: String,
    val uploadedParts: List<UploadedPartDto> = emptyList()
)

@Serializable
data class UploadResponse(val upload: UploadDto)

@Serializable
data class UploadCompleteRequest(
    val deviceId: String,
    val idempotencyKey: String
)

@Serializable
data class UploadPartResponse(val part: UploadedPartDto)

@Serializable
data class FileOperationRequest(
    val deviceId: String,
    val operationId: String,
    val type: String,
    val uploadId: String? = null,
    val fileId: String? = null,
    val baseVersion: Int = 0,
    val newRelativePath: String? = null,
    /** 用户已确认的批量删除：显式跳过服务端批量删除阈值。 */
    val confirmBulkDelete: Boolean = false
)

@Serializable
data class RemoteSyncFileDto(
    val fileId: String,
    val spaceId: String,
    val relativePath: String,
    val fileName: String,
    val objectId: String,
    val size: String,
    val sha256: String,
    val version: Int,
    val lastModifiedByDeviceId: String,
    val createdAt: String,
    val updatedAt: String,
    val isDeleted: Boolean = false
)

/**
 * 统一事件负载：所有字段均可选，按事件类型取值。
 * - file_created / file_updated：fileId / relativePath / fileName / objectId / size / sha256 / version（updated 另含 baseVersion）
 * - file_deleted：fileId / relativePath / fileName / version / baseVersion（不含 objectId）
 * - file_renamed：fileId / oldPath / newPath / oldFileName / newFileName / version / baseVersion
 */
@Serializable
data class SyncEventPayloadDto(
    val fileId: String? = null,
    val relativePath: String? = null,
    val fileName: String? = null,
    val objectId: String? = null,
    val size: String? = null,
    val sha256: String? = null,
    val version: Int? = null,
    val baseVersion: Int? = null,
    val oldPath: String? = null,
    val newPath: String? = null,
    val oldFileName: String? = null,
    val newFileName: String? = null,
    val lastModifiedByDeviceId: String? = null
)

@Serializable
data class SyncEventDto(
    val revision: String,
    val type: String,
    val deviceId: String,
    val createdAt: String,
    val payload: SyncEventPayloadDto? = null
)

@Serializable
data class FileOperationResponse(
    val file: RemoteSyncFileDto,
    val event: SyncEventDto,
    val duplicate: Boolean = false
)

@Serializable
data class ChangesResponse(
    val events: List<SyncEventDto> = emptyList(),
    val nextRevision: String,
    val hasMore: Boolean,
    val serverRevision: String
)

@Serializable
data class SyncAckRequest(
    val deviceId: String,
    val lastAppliedRevision: String
)

@Serializable
data class SyncAckResponse(
    val spaceId: String,
    val lastAppliedRevision: String,
    val serverRevision: String
)

/** 账号级同步锁请求：抢锁 / 心跳 / 释放共用。 */
@Serializable
data class SyncLockRequest(val deviceId: String)

@Serializable
data class SyncLockResponse(
    val acquired: Boolean,
    val holderDeviceId: String? = null,
    val expiresAt: Long? = null
)

@Serializable
data class SpaceSyncStatusResponse(
    val spaceId: String,
    val serverRevision: String,
    val lastAppliedRevision: String,
    val pendingEvents: Int,
    val activeFileCount: Int
)

/** 空间云端存储统计：活动文件与回收站的数量/总大小（字节数为十进制字符串）。 */
@Serializable
data class SpaceStorageStatsResponse(
    val activeFileCount: Int,
    val activeSize: String,
    val trashCount: Int,
    val trashSize: String
)

@Serializable
data class RemoteFileVersionDto(
    val fileId: String? = null,
    val objectId: String,
    val size: String,
    val sha256: String,
    val version: Int,
    val createdAt: String? = null
)

@Serializable
data class FileVersionsResponse(val versions: List<RemoteFileVersionDto> = emptyList())

/** 按路径查询云端占用文件的响应；file = null 表示该路径当前未被占用。 */
@Serializable
data class FileByPathResponse(val file: RemoteFileVersionDto? = null)

// ---------- trash ----------

@Serializable
data class TrashItemDto(
    val trashId: String,
    val spaceId: String,
    val fileId: String,
    val relativePath: String,
    val fileName: String,
    val size: String,
    val sha256: String,
    val version: Int,
    val deletedByDeviceId: String,
    val deletedAt: String,
    val purgeAfter: String
)

@Serializable
data class TrashResponse(val items: List<TrashItemDto> = emptyList())

@Serializable
data class RestoreTrashRequest(val deviceId: String, val operationId: String)

@Serializable
data class RestoreTrashResponse(
    val file: RemoteSyncFileDto,
    val event: SyncEventDto
)

@Serializable
data class PurgeTrashResponse(val purged: Boolean = false, val trashId: String = "")

// ---------- conflicts ----------

@Serializable
data class ConflictSubmittedDto(
    val objectId: String,
    val size: String,
    val sha256: String,
    val relativePath: String,
    val fileName: String,
    val uploadId: String? = null,
    val deviceId: String
)

@Serializable
data class ConflictDto(
    val conflictId: String,
    val spaceId: String,
    val fileId: String,
    val relativePath: String,
    val fileName: String,
    val serverVersion: Int,
    val submitted: ConflictSubmittedDto,
    val status: String,
    val resolvedAt: String? = null,
    val createdAt: String
)

@Serializable
data class ConflictsResponse(val conflicts: List<ConflictDto> = emptyList())

@Serializable
data class ResolveConflictRequest(
    val deviceId: String,
    val operationId: String,
    val resolution: String
)

@Serializable
data class ResolveConflictResponse(
    val conflict: ConflictDto,
    val file: RemoteSyncFileDto? = null,
    val event: SyncEventDto
)
