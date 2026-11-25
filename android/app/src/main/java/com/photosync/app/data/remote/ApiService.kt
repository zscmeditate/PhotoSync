package com.photosync.app.data.remote

import retrofit2.Response
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * 服务端 API 契约（前缀 /api/v1）。
 * 基础地址由用户在"服务器配置"页设置，通过 BaseUrlInterceptor 动态改写。
 */
interface ApiService {

    // ---------- health ----------

    @GET("api/v1/health")
    suspend fun health(): Response<HealthResponse>

    // ---------- auth ----------

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<AuthResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<AuthResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<TokenResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

    // ---------- devices ----------

    @POST("api/v1/devices/register")
    suspend fun registerDevice(@Body body: DeviceRegisterRequest): Response<DeviceResponse>

    @GET("api/v1/devices")
    suspend fun getDevices(): Response<DevicesResponse>

    @PATCH("api/v1/devices/{deviceId}")
    suspend fun renameDevice(
        @Path("deviceId") deviceId: String,
        @Body body: DeviceRenameRequest
    ): Response<DeviceResponse>

    @DELETE("api/v1/devices/{deviceId}")
    suspend fun deleteDevice(@Path("deviceId") deviceId: String): Response<Unit>

    /** 主设备把已移除设备从列表清理（记录保留，重新申请会再次出现） */
    @DELETE("api/v1/devices/{deviceId}/record")
    suspend fun purgeRemovedDevice(@Path("deviceId") deviceId: String): Response<Unit>

    /** 将本机（X-Device-Id）设为主设备；已有主设备时返回 409 */
    @POST("api/v1/devices/primary")
    suspend fun claimPrimaryDevice(): Response<DeviceResponse>

    /** 本机放弃主设备身份 */
    @DELETE("api/v1/devices/primary")
    suspend fun releasePrimaryDevice(): Response<Unit>

    /** 被移除的本机申请重新加入（等待主设备同意） */
    @POST("api/v1/devices/rejoin")
    suspend fun requestRejoinDevice(): Response<DeviceResponse>

    /** 主设备同意被移除设备重新加入 */
    @POST("api/v1/devices/{deviceId}/rejoin/approve")
    suspend fun approveDeviceRejoin(@Path("deviceId") deviceId: String): Response<DeviceResponse>

    /** 主设备拒绝被移除设备的重新加入申请 */
    @POST("api/v1/devices/{deviceId}/rejoin/reject")
    suspend fun rejectDeviceRejoin(@Path("deviceId") deviceId: String): Response<Unit>

    // ---------- spaces ----------

    @POST("api/v1/spaces")
    suspend fun createSpace(@Body body: SpaceCreateRequest): Response<SpaceResponse>

    @GET("api/v1/spaces")
    suspend fun getSpaces(@Query("deviceId") deviceId: String? = null): Response<SpacesResponse>

    @GET("api/v1/spaces/{spaceId}")
    suspend fun getSpace(
        @Path("spaceId") spaceId: String,
        @Query("deviceId") deviceId: String? = null
    ): Response<SpaceDetailResponse>

    @PATCH("api/v1/spaces/{spaceId}")
    suspend fun updateSpace(
        @Path("spaceId") spaceId: String,
        @Body body: SpaceUpdateRequest
    ): Response<SpaceResponse>

    @DELETE("api/v1/spaces/{spaceId}")
    suspend fun deleteSpace(
        @Path("spaceId") spaceId: String,
        @Query("deviceId") deviceId: String
    ): Response<Unit>

    @POST("api/v1/spaces/{spaceId}/members")
    suspend fun addSpaceMember(
        @Path("spaceId") spaceId: String,
        @Body body: MemberAddRequest
    ): Response<MemberResponse>

    @DELETE("api/v1/spaces/{spaceId}/members/{deviceId}")
    suspend fun removeSpaceMember(
        @Path("spaceId") spaceId: String,
        @Path("deviceId") deviceId: String,
        @Query("operatorDeviceId") operatorDeviceId: String
    ): Response<Unit>

    @POST("api/v1/spaces/{spaceId}/join-requests")
    suspend fun requestJoinSpace(
        @Path("spaceId") spaceId: String,
        @Body body: MemberAddRequest
    ): Response<JoinRequestSubmitResponse>

    @GET("api/v1/spaces/{spaceId}/join-requests")
    suspend fun getJoinRequests(
        @Path("spaceId") spaceId: String,
        @Query("deviceId") deviceId: String
    ): Response<JoinRequestListResponse>

    @POST("api/v1/join-requests/{requestId}/approve")
    suspend fun approveJoinRequest(
        @Path("requestId") requestId: String,
        @Body body: MemberAddRequest
    ): Response<JoinRequestResponse>

    @POST("api/v1/join-requests/{requestId}/reject")
    suspend fun rejectJoinRequest(
        @Path("requestId") requestId: String,
        @Body body: MemberAddRequest
    ): Response<JoinRequestResponse>

    // ---------- folder mappings ----------

    @POST("api/v1/spaces/{spaceId}/mappings")
    suspend fun createMapping(
        @Path("spaceId") spaceId: String,
        @Body body: MappingCreateRequest
    ): Response<MappingResponse>

    @GET("api/v1/spaces/{spaceId}/mappings")
    suspend fun getMappings(
        @Path("spaceId") spaceId: String,
        @Query("deviceId") deviceId: String
    ): Response<MappingsResponse>

    @GET("api/v1/devices/{deviceId}/mappings")
    suspend fun getDeviceMappings(
        @Path("deviceId") deviceId: String
    ): Response<MappingsResponse>

    @PATCH("api/v1/mappings/{mappingId}")
    suspend fun updateMapping(
        @Path("mappingId") mappingId: String,
        @Body body: MappingUpdateRequest
    ): Response<MappingResponse>

    @DELETE("api/v1/mappings/{mappingId}")
    suspend fun deleteMapping(@Path("mappingId") mappingId: String): Response<Unit>

    // ---------- scan manifest ----------

    @POST("api/v1/spaces/{spaceId}/scan-manifest")
    suspend fun scanManifest(
        @Path("spaceId") spaceId: String,
        @Body body: ScanManifestRequest
    ): Response<ScanManifestResponse>

    // ---------- incremental sync ----------

    @POST("api/v1/uploads")
    suspend fun createUpload(@Body body: UploadCreateRequest): Response<UploadResponse>

    @GET("api/v1/uploads/{uploadId}")
    suspend fun getUpload(
        @Path("uploadId") uploadId: String,
        @Query("deviceId") deviceId: String
    ): Response<UploadResponse>

    @PUT("api/v1/uploads/{uploadId}/parts/{partNumber}")
    suspend fun uploadPart(
        @Path("uploadId") uploadId: String,
        @Path("partNumber") partNumber: Int,
        @Query("deviceId") deviceId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RequestBody
    ): Response<UploadPartResponse>

    @POST("api/v1/uploads/{uploadId}/complete")
    suspend fun completeUpload(
        @Path("uploadId") uploadId: String,
        @Body body: UploadCompleteRequest
    ): Response<UploadResponse>

    @POST("api/v1/spaces/{spaceId}/operations")
    suspend fun createFileOperation(
        @Path("spaceId") spaceId: String,
        @Body body: FileOperationRequest
    ): Response<FileOperationResponse>

    @GET("api/v1/spaces/{spaceId}/changes")
    suspend fun getChanges(
        @Path("spaceId") spaceId: String,
        @Query("deviceId") deviceId: String,
        @Query("sinceRevision") sinceRevision: String,
        @Query("limit") limit: Int = 100
    ): Response<ChangesResponse>

    @POST("api/v1/spaces/{spaceId}/ack")
    suspend fun acknowledgeChanges(
        @Path("spaceId") spaceId: String,
        @Body body: SyncAckRequest
    ): Response<SyncAckResponse>

    @GET("api/v1/spaces/{spaceId}/status")
    suspend fun getSyncStatus(
        @Path("spaceId") spaceId: String,
        @Query("deviceId") deviceId: String
    ): Response<SpaceSyncStatusResponse>

    @GET("api/v1/spaces/{spaceId}/storage")
    suspend fun getStorageStats(@Path("spaceId") spaceId: String): Response<SpaceStorageStatsResponse>

    // ---------- sync lock（账号级同步权） ----------

    @POST("api/v1/sync-lock/acquire")
    suspend fun acquireSyncLock(@Body body: SyncLockRequest): Response<SyncLockResponse>

    @POST("api/v1/sync-lock/heartbeat")
    suspend fun heartbeatSyncLock(@Body body: SyncLockRequest): Response<SyncLockResponse>

    @POST("api/v1/sync-lock/release")
    suspend fun releaseSyncLock(@Body body: SyncLockRequest): Response<SyncLockResponse>

    @Streaming
    @GET("api/v1/objects/{objectId}/download")
    suspend fun downloadObject(
        @Path("objectId") objectId: String,
        @Query("deviceId") deviceId: String,
        @Header("Range") range: String? = null
    ): Response<ResponseBody>

    @GET("api/v1/files/{fileId}/versions")
    suspend fun getFileVersions(
        @Path("fileId") fileId: String,
        @Query("deviceId") deviceId: String
    ): Response<FileVersionsResponse>

    @GET("api/v1/spaces/{spaceId}/files/by-path")
    suspend fun getFileByPath(
        @Path("spaceId") spaceId: String,
        @Query("deviceId") deviceId: String,
        @Query("relativePath") relativePath: String
    ): Response<FileByPathResponse>

    // ---------- trash ----------

    @GET("api/v1/spaces/{spaceId}/trash")
    suspend fun getTrash(
        @Path("spaceId") spaceId: String,
        @Query("deviceId") deviceId: String
    ): Response<TrashResponse>

    @POST("api/v1/trash/{trashId}/restore")
    suspend fun restoreTrash(
        @Path("trashId") trashId: String,
        @Body body: RestoreTrashRequest
    ): Response<RestoreTrashResponse>

    @DELETE("api/v1/trash/{trashId}")
    suspend fun purgeTrash(
        @Path("trashId") trashId: String,
        @Query("deviceId") deviceId: String
    ): Response<PurgeTrashResponse>

    // ---------- conflicts ----------

    @GET("api/v1/spaces/{spaceId}/conflicts")
    suspend fun getConflicts(
        @Path("spaceId") spaceId: String,
        @Query("deviceId") deviceId: String
    ): Response<ConflictsResponse>

    @POST("api/v1/conflicts/{conflictId}/resolve")
    suspend fun resolveConflict(
        @Path("conflictId") conflictId: String,
        @Body body: ResolveConflictRequest
    ): Response<ResolveConflictResponse>
}
