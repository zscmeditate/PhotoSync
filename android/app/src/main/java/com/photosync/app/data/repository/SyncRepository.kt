package com.photosync.app.data.repository

import androidx.room.withTransaction
import com.photosync.app.data.local.FileSyncStatus
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSyncCursor
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.data.local.LocalSyncTaskDao
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.local.SyncDatabase
import com.photosync.app.data.local.SyncTaskState
import com.photosync.app.data.local.SyncTaskStats
import com.photosync.app.data.local.SyncTaskType
import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.RemoteFileDto
import com.photosync.app.data.sync.FolderScanProgress
import com.photosync.app.data.sync.LocalChange
import com.photosync.app.data.sync.LocalChangeDetector
import com.photosync.app.data.sync.RemoteDownloadPlanner
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Phase 3 同步任务、索引与游标的持久化入口。 */
class SyncRepository(
    val apiClient: ApiClient,
    private val tokenStore: SecureTokenStore,
    private val database: SyncDatabase,
    private val spaceRepository: SpaceRepository
) {
    private val spaceDao = database.localSpaceDao()
    private val mappingDao = database.localFolderMappingDao()
    private val fileDao = database.localFileIndexDao()
    private val taskDao = database.localSyncTaskDao()
    private val cursorDao = database.localSyncCursorDao()

    fun observeStats(spaceId: String): Flow<SyncTaskStats> = taskDao.observeStats(spaceId)

    fun observeActiveTasks(): Flow<List<LocalSyncTask>> = taskDao.observeActive()

    fun observeRecentTasks(limit: Int = 200): Flow<List<LocalSyncTask>> = taskDao.observeRecent(limit)

    fun observeRecentTasks(spaceId: String, limit: Int = 200): Flow<List<LocalSyncTask>> =
        taskDao.observeRecentBySpace(spaceId, limit)

    suspend fun retryTask(taskId: String) = taskDao.retryTask(taskId, System.currentTimeMillis())

    suspend fun cancelTask(taskId: String) = taskDao.cancelTask(taskId)

    fun currentDeviceId(): String? = tokenStore.currentDeviceId()

    suspend fun spaceIds(): List<String> = spaceDao.getAll().map { it.id }

    suspend fun activeMappings(spaceId: String, deviceId: String): List<LocalFolderMapping> =
        mappingDao.getBySpaceAndDevice(spaceId, deviceId).filter { it.status == MappingStatus.ACTIVE }

    suspend fun mapping(mappingId: String): LocalFolderMapping? = mappingDao.getById(mappingId)

    suspend fun scanMapping(
        mappingId: String,
        onProgress: (FolderScanProgress) -> Unit = {}
    ): ApiResult<List<LocalFileIndex>> = spaceRepository.scanMappingLocally(mappingId, onProgress)

    suspend fun files(mappingId: String): List<LocalFileIndex> = fileDao.getByMapping(mappingId)

    suspend fun file(mappingId: String, relativePath: String): LocalFileIndex? =
        fileDao.getByPath(mappingId, relativePath)

    suspend fun task(id: String): LocalSyncTask? = taskDao.getById(id)

    suspend fun taskForEvent(spaceId: String, revision: String): LocalSyncTask? =
        taskDao.getByEvent(spaceId, revision)

    suspend fun saveTask(task: LocalSyncTask) = taskDao.upsert(task)

    suspend fun nextPendingUpload(spaceId: String): LocalSyncTask? = taskDao.nextPendingUpload(spaceId)

    suspend fun nextPendingMutation(spaceId: String): LocalSyncTask? = taskDao.nextPendingMutation(spaceId)

    /** 为冲突副本等本地新文件直接登记上传任务（baseVersion = 0，作为新文件提交）。 */
    suspend fun enqueueNewFileUpload(
        spaceId: String,
        mapping: LocalFolderMapping,
        localPath: String,
        size: Long,
        sha256: String?
    ) {
        database.withTransaction {
            enqueueUploadTask(
                spaceId = spaceId,
                mapping = mapping,
                localPath = localPath,
                size = size,
                sha256 = sha256,
                baseVersion = null,
                fileId = null,
                now = System.currentTimeMillis()
            )
        }
    }

    suspend fun recoverRunning(spaceId: String) =
        taskDao.recoverRunning(spaceId, System.currentTimeMillis())

    suspend fun requeueFailed(spaceId: String) =
        taskDao.requeueFailed(spaceId, System.currentTimeMillis())

    suspend fun cursor(spaceId: String, deviceId: String): LocalSyncCursor? =
        cursorDao.get(spaceId, deviceId)

    suspend fun saveCursor(cursor: LocalSyncCursor) = cursorDao.upsert(cursor)

    suspend fun updateUploadProgress(
        taskId: String,
        uploadedParts: Int,
        bytesTransferred: Long,
        nextPartNumber: Int
    ) = taskDao.updateUploadProgress(
        taskId = taskId,
        uploadedParts = uploadedParts,
        bytesTransferred = bytesTransferred,
        nextPartNumber = nextPartNumber,
        updatedAt = System.currentTimeMillis()
    )

    suspend fun deleteIndex(mappingId: String, relativePath: String) {
        fileDao.deleteByPath(mappingId, relativePath)
    }

    /** 远端 file_renamed 事件应用：把索引行从旧路径迁移到新路径并标记已同步。 */
    suspend fun moveIndex(
        mappingId: String,
        oldRelativePath: String,
        newRelativePath: String,
        newFileName: String,
        fileId: String,
        version: Int,
        docUri: String,
        size: Long,
        sha256: String?
    ) {
        database.withTransaction {
            fileDao.deleteByPath(mappingId, newRelativePath)
            val existing = fileDao.getByPath(mappingId, oldRelativePath)
            val now = System.currentTimeMillis()
            fileDao.upsert(
                LocalFileIndex(
                    id = existing?.id ?: 0,
                    mappingId = mappingId,
                    relativePath = newRelativePath,
                    fileName = newFileName,
                    size = size,
                    modifiedAt = existing?.modifiedAt ?: now,
                    sha256 = sha256,
                    docUri = docUri,
                    status = FileSyncStatus.SYNCED,
                    lastScannedAt = existing?.lastScannedAt ?: now,
                    remoteFileId = fileId,
                    remoteVersion = version
                )
            )
            if (oldRelativePath != newRelativePath) {
                fileDao.deleteByPath(mappingId, oldRelativePath)
            }
        }
    }

    /**
     * 比较扫描前索引与扫描后索引，规划修改/删除/重命名/新增上传任务并落库。
     * 取代 Phase 3 的 enqueueNewUploads：单一入口，靠活动任务计数去重，绝不重复建任务。
     */
    suspend fun planAndEnqueueLocalChanges(
        spaceId: String,
        mapping: LocalFolderMapping,
        oldIndex: List<LocalFileIndex>,
        newIndex: List<LocalFileIndex>
    ) {
        database.withTransaction {
            val changes = LocalChangeDetector.detect(oldIndex, newIndex)
            val now = System.currentTimeMillis()
            val coveredPaths = mutableSetOf<String>()
            for (change in changes) {
                when (change) {
                    is LocalChange.UploadNew -> {
                        coveredPaths.add(change.relativePath)
                        enqueueUploadTask(
                            spaceId, mapping, change.relativePath, change.size, change.sha256,
                            baseVersion = null, fileId = null, now
                        )
                    }
                    is LocalChange.Modify -> {
                        coveredPaths.add(change.relativePath)
                        markIndexPendingUpload(mapping.id, change.relativePath)
                        enqueueUploadTask(
                            spaceId, mapping, change.relativePath, change.size, change.sha256,
                            baseVersion = change.baseVersion, fileId = change.fileId, now
                        )
                    }
                    is LocalChange.Delete -> {
                        coveredPaths.add(change.relativePath)
                        enqueueMutationTask(
                            spaceId, mapping, SyncTaskType.DELETE,
                            localPath = change.relativePath, remotePath = change.relativePath,
                            fileId = change.fileId, baseVersion = change.baseVersion,
                            oldLocalRelativePath = null, now
                        )
                    }
                    is LocalChange.Rename -> {
                        coveredPaths.add(change.newRelativePath)
                        enqueueMutationTask(
                            spaceId, mapping, SyncTaskType.RENAME,
                            localPath = change.newRelativePath,
                            remotePath = RemoteDownloadPlanner.remotePath(change.newRelativePath),
                            fileId = change.fileId, baseVersion = change.baseVersion,
                            oldLocalRelativePath = change.oldRelativePath, now
                        )
                    }
                }
            }
            // 兜底：仍为 PENDING_UPLOAD 但未被变更覆盖、且无活动任务的索引 → 补建上传任务
            for (file in newIndex) {
                if (file.status != FileSyncStatus.PENDING_UPLOAD) continue
                if (file.relativePath in coveredPaths) continue
                val remotePath = RemoteDownloadPlanner.remotePath(file.relativePath)
                if (taskDao.countActiveTasks(spaceId, mapping.id, remotePath) > 0) continue
                enqueueUploadTask(
                    spaceId, mapping, file.relativePath, file.size, file.sha256,
                    baseVersion = null, fileId = file.remoteFileId, now
                )
            }
        }
    }

    private suspend fun enqueueUploadTask(
        spaceId: String,
        mapping: LocalFolderMapping,
        localPath: String,
        size: Long,
        sha256: String?,
        baseVersion: Int?,
        fileId: String?,
        now: Long
    ) {
        val remotePath = RemoteDownloadPlanner.remotePath(localPath)
        if (taskDao.countActiveTasks(spaceId, mapping.id, remotePath) > 0) return
        val task = LocalSyncTask(
            id = UUID.randomUUID().toString(),
            spaceId = spaceId,
            mappingId = mapping.id,
            type = SyncTaskType.UPLOAD,
            state = SyncTaskState.PENDING,
            localRelativePath = localPath,
            remoteRelativePath = remotePath,
            fileId = fileId,
            size = size,
            sha256 = sha256,
            baseVersion = baseVersion,
            operationId = "",
            createdAt = now,
            updatedAt = now
        ).let { it.copy(operationId = it.id) }
        taskDao.insertIfAbsent(task)
    }

    private suspend fun enqueueMutationTask(
        spaceId: String,
        mapping: LocalFolderMapping,
        type: String,
        localPath: String,
        remotePath: String,
        fileId: String,
        baseVersion: Int,
        oldLocalRelativePath: String?,
        now: Long
    ) {
        if (taskDao.countActiveTasks(spaceId, mapping.id, remotePath) > 0) return
        val task = LocalSyncTask(
            id = UUID.randomUUID().toString(),
            spaceId = spaceId,
            mappingId = mapping.id,
            type = type,
            state = SyncTaskState.PENDING,
            localRelativePath = localPath,
            remoteRelativePath = remotePath,
            fileId = fileId,
            size = 0L,
            baseVersion = baseVersion,
            oldLocalRelativePath = oldLocalRelativePath,
            operationId = "",
            createdAt = now,
            updatedAt = now
        ).let { it.copy(operationId = it.id) }
        taskDao.insertIfAbsent(task)
    }

    /**
     * 新绑定/重新绑定的映射：把本地已存在且内容一致的远端文件直接建立关联，
     * 避免重新扫描后把已有文件误判为新上传而触发 FILE_ALREADY_EXISTS。
     */
    suspend fun reconcileRemoteLinks(mappingId: String, remoteFiles: List<RemoteFileDto>) {
        if (remoteFiles.isEmpty()) return
        database.withTransaction {
            val localFiles = fileDao.getByMapping(mappingId)
            if (localFiles.isEmpty()) return@withTransaction
            val remoteByPath = remoteFiles.associateBy { it.relativePath }
            for (local in localFiles) {
                if (!local.remoteFileId.isNullOrBlank()) continue
                val remote = remoteByPath[local.relativePath] ?: continue
                val localSha = local.sha256
                val remoteSha = remote.sha256
                if (localSha.isNullOrBlank() || remoteSha.isNullOrBlank()) continue
                if (local.size != remote.size) continue
                if (!localSha.equals(remoteSha, ignoreCase = true)) continue
                fileDao.upsert(
                    local.copy(
                        status = FileSyncStatus.SYNCED,
                        remoteFileId = remote.fileId,
                        remoteVersion = remote.version
                    )
                )
            }
        }
    }

    private suspend fun markIndexPendingUpload(mappingId: String, relativePath: String) {
        fileDao.getByPath(mappingId, relativePath)?.let { old ->
            if (old.status != FileSyncStatus.PENDING_UPLOAD) {
                fileDao.upsert(old.copy(status = FileSyncStatus.PENDING_UPLOAD))
            }
        }
    }

    suspend fun getOrCreateDownloadTask(
        spaceId: String,
        mappingId: String,
        localPath: String,
        remotePath: String,
        fileId: String,
        objectId: String,
        size: Long,
        sha256: String,
        revision: String
    ): LocalSyncTask {
        taskForEvent(spaceId, revision)?.let { return it }
        val now = System.currentTimeMillis()
        val task = LocalSyncTask(
            id = UUID.randomUUID().toString(),
            spaceId = spaceId,
            mappingId = mappingId,
            type = SyncTaskType.DOWNLOAD,
            state = SyncTaskState.PENDING,
            localRelativePath = localPath,
            remoteRelativePath = remotePath,
            fileId = fileId,
            objectId = objectId,
            size = size,
            sha256 = sha256,
            operationId = "",
            eventRevision = revision,
            createdAt = now,
            updatedAt = now
        ).let { it.copy(operationId = it.id) }
        taskDao.insertIfAbsent(task)
        return taskForEvent(spaceId, revision)
            ?: throw IllegalStateException("下载任务持久化失败")
    }

    /**
     * 为非下载类远端事件（file_deleted / file_renamed）创建一个按 revision 去重的标记任务，
     * 用于持久化应用结果、阻塞游标（失败时），与下载任务共享 (spaceId, eventRevision) 唯一约束。
     */
    suspend fun getOrCreateEventMarkerTask(
        spaceId: String,
        mappingId: String,
        localPath: String,
        remotePath: String,
        fileId: String,
        revision: String
    ): LocalSyncTask {
        taskForEvent(spaceId, revision)?.let { return it }
        val now = System.currentTimeMillis()
        val task = LocalSyncTask(
            id = UUID.randomUUID().toString(),
            spaceId = spaceId,
            mappingId = mappingId,
            type = SyncTaskType.DOWNLOAD,
            state = SyncTaskState.PENDING,
            localRelativePath = localPath,
            remoteRelativePath = remotePath,
            fileId = fileId,
            size = 0L,
            operationId = "",
            eventRevision = revision,
            createdAt = now,
            updatedAt = now
        ).let { it.copy(operationId = it.id) }
        taskDao.insertIfAbsent(task)
        return taskForEvent(spaceId, revision)
            ?: throw IllegalStateException("事件任务持久化失败")
    }

    suspend fun markTaskRunning(task: LocalSyncTask): LocalSyncTask {
        val running = task.copy(
            state = SyncTaskState.RUNNING,
            attempt = task.attempt + 1,
            error = null,
            updatedAt = System.currentTimeMillis()
        )
        database.withTransaction {
            taskDao.upsert(running)
            fileDao.getByPath(task.mappingId, task.localRelativePath)?.let { old ->
                fileDao.upsert(
                    old.copy(
                        status = if (task.type == SyncTaskType.UPLOAD) {
                            FileSyncStatus.PENDING_UPLOAD
                        } else {
                            FileSyncStatus.PENDING_DOWNLOAD
                        }
                    )
                )
            }
        }
        return running
    }

    suspend fun markTaskRetry(task: LocalSyncTask, code: String, message: String) {
        taskDao.upsert(
            task.copy(
                state = SyncTaskState.RETRY,
                error = "$code：$message",
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markTaskFailed(
        task: LocalSyncTask,
        code: String,
        message: String,
        conflictingIndex: LocalFileIndex? = null
    ) {
        database.withTransaction {
            taskDao.upsert(
                task.copy(
                    state = SyncTaskState.FAILED,
                    error = "$code：$message",
                    updatedAt = System.currentTimeMillis()
                )
            )
            val index = conflictingIndex ?: fileDao.getByPath(task.mappingId, task.localRelativePath)
            if (index != null) {
                fileDao.upsert(
                    index.copy(
                        status = if (code == "LOCAL_PATH_CONFLICT") {
                            FileSyncStatus.CONFLICT
                        } else {
                            FileSyncStatus.SYNC_ERROR
                        }
                    )
                )
            }
        }
    }

    suspend fun markSynced(task: LocalSyncTask, index: LocalFileIndex) {
        database.withTransaction {
            taskDao.upsert(
                task.copy(
                    state = SyncTaskState.COMPLETED,
                    bytesTransferred = task.size,
                    error = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            fileDao.upsert(index.copy(status = FileSyncStatus.SYNCED))
        }
    }

    /** 仅把任务标记为完成，不触碰索引（用于删除/重命名等索引已在别处更新的场景）。 */
    suspend fun completeTask(task: LocalSyncTask) {
        taskDao.upsert(
            task.copy(
                state = SyncTaskState.COMPLETED,
                error = null,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction(block)
}
