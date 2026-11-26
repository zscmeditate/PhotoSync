package com.photosync.app.data.repository

import androidx.room.withTransaction
import com.photosync.app.data.local.FileSyncStatus
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalPendingDeletion
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
import com.photosync.app.data.sync.BulkDeletionPolicy
import com.photosync.app.data.sync.FolderScanProgress
import com.photosync.app.data.sync.LocalChange
import com.photosync.app.data.sync.LocalChangeDetector
import com.photosync.app.data.sync.MediaFileFilter
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
    private val pendingDeletionDao = database.localPendingDeletionDao()

    fun observeStats(spaceId: String): Flow<SyncTaskStats> = taskDao.observeStats(spaceId)

    /** 空间详情的任务列表：该空间的进行中、待处理与失败任务，按空间隔离。 */
    fun observeActiveTasks(spaceId: String): Flow<List<LocalSyncTask>> =
        taskDao.observeActiveBySpace(spaceId)

    fun observeRecentTasks(limit: Int = 200): Flow<List<LocalSyncTask>> = taskDao.observeRecent(limit)

    fun observeRecentTasks(spaceId: String, limit: Int = 200): Flow<List<LocalSyncTask>> =
        taskDao.observeRecentBySpace(spaceId, limit)

    suspend fun retryTask(taskId: String) = taskDao.retryTask(taskId, System.currentTimeMillis())

    suspend fun cancelTask(taskId: String) = taskDao.cancelTask(taskId)

    /** 清空指定空间的已完成任务记录。 */
    suspend fun clearCompletedBySpace(spaceId: String) = taskDao.clearCompletedBySpace(spaceId)

    /** 清空指定空间的全部同步任务（同步日志），返回删除行数。 */
    suspend fun clearTasksBySpace(spaceId: String) = taskDao.clearTasksBySpace(spaceId)

    /** 清空全部同步任务（同步日志），返回删除行数。 */
    suspend fun clearAllTasks() = taskDao.clearAllTasks()

    /** 取消进行中的上传/下载：保留任务并移到队列末尾，而不是丢弃。 */
    suspend fun moveRunningTaskToQueueEnd(taskId: String) =
        taskDao.moveRunningTaskToQueueEnd(taskId, System.currentTimeMillis())

    /** 按 ID 查询任务：引擎用于感知运行中任务是否被用户取消（行已删除）。 */
    suspend fun getTask(taskId: String) = taskDao.getById(taskId)

    fun currentDeviceId(): String? = tokenStore.currentDeviceId()

    suspend fun spaceIds(): List<String> = spaceDao.getAll().map { it.id }

    /** 空间名称：通知栏进度展示用。 */
    suspend fun spaceName(spaceId: String): String? = spaceDao.getById(spaceId)?.name

    /**
     * 参与同步的映射。ERROR 也参与：每次同步重试扫描（成功即自动恢复 ACTIVE），
     * 否则一次扫描异常会让目录永久退出同步，队列中的下载任务永远不被执行。
     * ACCESS_REVOKED 不参与，需用户重新授权。
     */
    suspend fun syncMappings(spaceId: String, deviceId: String): List<LocalFolderMapping> =
        mappingDao.getBySpaceAndDevice(spaceId, deviceId).filter {
            it.status == MappingStatus.ACTIVE || it.status == MappingStatus.ERROR
        }

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

    /** 应用进程启动时调用：上次进程被杀时残留的 RUNNING 任务实际已中断，退回队列等待重新执行。 */
    suspend fun recoverStaleRunning(processStartAt: Long) =
        taskDao.recoverStaleRunning(processStartAt, System.currentTimeMillis())

    /** 恢复上次同步被中断后停留在“扫描中”的映射，否则它会被 syncMappings 过滤而永不参与同步。 */
    suspend fun recoverScanning(spaceId: String) = mappingDao.recoverScanning(spaceId)

    /** 失败处理补建任务前，移除同一路径上的旧活动任务，避免新任务被去重逻辑跳过。 */
    suspend fun removeActiveTasksForPath(spaceId: String, mappingId: String, localPath: String) =
        taskDao.deleteActiveTasksForRemotePath(
            spaceId = spaceId,
            mappingId = mappingId,
            remoteRelativePath = RemoteDownloadPlanner.remotePath(localPath)
        )

    /** 不依赖远端事件的独立下载任务（失败处理「云端优先」补建）。 */
    suspend fun nextStandaloneDownload(spaceId: String): LocalSyncTask? =
        taskDao.nextStandaloneDownload(spaceId)

    /** 队列直跑（「刷新」）用：任意待执行下载任务（含事件驱动任务）。 */
    suspend fun nextQueuedDownload(spaceId: String): LocalSyncTask? =
        taskDao.nextQueuedDownload(spaceId)

    /** 仍有未完成任务（待执行/进行中/重试/失败）的远端路径。 */
    suspend fun activeRemotePaths(spaceId: String): List<String> =
        taskDao.activeRemotePaths(spaceId)

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

    /** 下载进度：写入任务已传输字节数，任务列表据此显示实时进度条。 */
    suspend fun updateDownloadProgress(taskId: String, bytesTransferred: Long) =
        taskDao.updateDownloadProgress(
            taskId = taskId,
            bytesTransferred = bytesTransferred,
            updatedAt = System.currentTimeMillis()
        )

    suspend fun deleteIndex(mappingId: String, relativePath: String) {
        fileDao.deleteByPath(mappingId, relativePath)
    }

    /** 仅调整索引行状态（不触碰任务），用于冲突保护等场景。 */
    suspend fun markIndexStatus(mappingId: String, relativePath: String, status: String) {
        fileDao.getByPath(mappingId, relativePath)?.let {
            fileDao.upsert(it.copy(status = status))
        }
    }

    /** 清理源文件已不存在的失败上传任务。 */
    suspend fun deleteFailedUploadsWithoutLocalFile(spaceId: String) =
        taskDao.deleteFailedUploadsWithoutLocalFile(spaceId)

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
            // 系统回收站条目（.trashed-* / .Trash）不再传播删除：
            // 服务端启动维护会统一软删除并广播 file_deleted 事件，这里跳过以免重复删除。
            // 兜底：即使扫描器漏掉回收站条目，也绝不上传回收站路径；
            // 被误判为「改名为回收站名」的变更按原文件删除处理。
            val changes = LocalChangeDetector.detect(oldIndex, newIndex).flatMap { change ->
                when (change) {
                    is LocalChange.Delete ->
                        if (MediaFileFilter.isSystemTrashPath(change.relativePath)) {
                            emptyList()
                        } else {
                            listOf(change)
                        }
                    is LocalChange.UploadNew ->
                        if (MediaFileFilter.isSystemTrashPath(change.relativePath)) {
                            emptyList()
                        } else {
                            listOf(change)
                        }
                    is LocalChange.Modify ->
                        if (MediaFileFilter.isSystemTrashPath(change.relativePath)) {
                            emptyList()
                        } else {
                            listOf(change)
                        }
                    is LocalChange.Rename ->
                        if (MediaFileFilter.isSystemTrashPath(change.newRelativePath)) {
                            listOf(
                                LocalChange.Delete(
                                    relativePath = change.oldRelativePath,
                                    fileId = change.fileId,
                                    baseVersion = change.baseVersion,
                                    size = change.size
                                )
                            )
                        } else {
                            listOf(change)
                        }
                }
            }
            val now = System.currentTimeMillis()
            // 批量删除保护：删除数超过阈值时不静默丢弃，而是记录为「待确认删除」，
            // 由用户在空间详情二次确认后再删除云端；期间也不会被兜底补齐重新下载。
            val deletions = changes.filterIsInstance<LocalChange.Delete>()
            val pendingBulkDelete = BulkDeletionPolicy.requiresConfirmation(deletions.size)
            // 文件在本地重新出现（用户把移走的文件放回来了）时，撤销对应的待确认删除。
            val newPathSet = newIndex.mapTo(mutableSetOf()) { it.relativePath }
            val restoredPendingPaths = pendingDeletionDao.pathsByMapping(mapping.id)
                .filter { it in newPathSet }
            if (restoredPendingPaths.isNotEmpty()) {
                pendingDeletionDao.deleteByPaths(mapping.id, restoredPendingPaths)
            }
            if (pendingBulkDelete) {
                pendingDeletionDao.upsertAll(
                    deletions.map { change ->
                        LocalPendingDeletion(
                            mappingId = mapping.id,
                            spaceId = spaceId,
                            relativePath = change.relativePath,
                            fileId = change.fileId,
                            baseVersion = change.baseVersion,
                            size = change.size,
                            detectedAt = now
                        )
                    }
                )
            }
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
                        // 批量删除已转入待确认，本轮不提交删除任务。
                        if (pendingBulkDelete) continue
                        coveredPaths.add(change.relativePath)
                        enqueueMutationTask(
                            spaceId, mapping, SyncTaskType.DELETE,
                            localPath = change.relativePath, remotePath = change.relativePath,
                            fileId = change.fileId, baseVersion = change.baseVersion,
                            size = change.size, oldLocalRelativePath = null, now
                        )
                    }
                    is LocalChange.Rename -> {
                        coveredPaths.add(change.newRelativePath)
                        enqueueMutationTask(
                            spaceId, mapping, SyncTaskType.RENAME,
                            localPath = change.newRelativePath,
                            remotePath = RemoteDownloadPlanner.remotePath(change.newRelativePath),
                            fileId = change.fileId, baseVersion = change.baseVersion,
                            size = change.size, oldLocalRelativePath = change.oldRelativePath, now
                        )
                    }
                }
            }
            // 兜底：仍为 PENDING_UPLOAD 但未被变更覆盖、且无活动任务的索引 → 补建上传任务
            for (file in newIndex) {
                if (file.status != FileSyncStatus.PENDING_UPLOAD) continue
                if (file.relativePath in coveredPaths) continue
                if (MediaFileFilter.isSystemTrashPath(file.relativePath)) continue
                val remotePath = RemoteDownloadPlanner.remotePath(file.relativePath)
                if (taskDao.countActiveTasks(spaceId, mapping.id, remotePath) > 0) continue
                enqueueUploadTask(
                    spaceId, mapping, file.relativePath, file.size, file.sha256,
                    baseVersion = null, fileId = file.remoteFileId, now
                )
            }
            cancelTasksForVanishedFiles(spaceId, mapping, oldIndex, newIndex, changes, coveredPaths)
        }
    }

    /** 空间详情：待用户确认的批量删除数量。 */
    fun observePendingDeletionCount(spaceId: String): Flow<Int> =
        pendingDeletionDao.observeCount(spaceId)

    /** 待确认删除的路径：兜底补齐远端文件时必须跳过，避免刚移走的文件又被下载回来。 */
    suspend fun pendingDeletionPaths(spaceId: String): List<String> =
        pendingDeletionDao.pathsBySpace(spaceId)

    /** 用户确认批量删除：把待确认记录转成带确认标记的 DELETE 任务，并清空记录。 */
    suspend fun confirmPendingDeletions(spaceId: String) {
        database.withTransaction {
            val items = pendingDeletionDao.getBySpace(spaceId)
            val now = System.currentTimeMillis()
            for (item in items) {
                val mapping = mappingDao.getById(item.mappingId) ?: continue
                enqueueMutationTask(
                    spaceId, mapping, SyncTaskType.DELETE,
                    localPath = item.relativePath, remotePath = item.relativePath,
                    fileId = item.fileId, baseVersion = item.baseVersion,
                    size = item.size, oldLocalRelativePath = null, now,
                    confirmed = true
                )
            }
            pendingDeletionDao.deleteBySpace(spaceId)
        }
    }

    /** 用户选择保留云端文件：放弃这批待确认删除，下次同步会把它们重新下载回本地。 */
    suspend fun cancelPendingDeletions(spaceId: String) = pendingDeletionDao.deleteBySpace(spaceId)

    /**
     * 服务端批量删除阈值要求确认时，把该删除任务转为待确认记录，
     * 避免失败任务长期占位、也避免文件被兜底补齐重新下载。
     */
    suspend fun recordPendingDeletion(task: LocalSyncTask) {
        val fileId = task.fileId ?: return
        database.withTransaction {
            pendingDeletionDao.upsertAll(
                listOf(
                    LocalPendingDeletion(
                        mappingId = task.mappingId,
                        spaceId = task.spaceId,
                        relativePath = task.remoteRelativePath,
                        fileId = fileId,
                        baseVersion = task.baseVersion ?: 1,
                        size = task.size,
                        detectedAt = System.currentTimeMillis()
                    )
                )
            )
            taskDao.cancelTask(task.id)
        }
    }

    /**
     * 扫描后消失、且没有产生任何同步变更的本地文件（典型：被系统识别为回收站条目
     * 而过滤出扫描），撤销其未执行的上传任务，使其彻底不参与同步。
     */
    private suspend fun cancelTasksForVanishedFiles(
        spaceId: String,
        mapping: LocalFolderMapping,
        oldIndex: List<LocalFileIndex>,
        newIndex: List<LocalFileIndex>,
        changes: List<LocalChange>,
        coveredPaths: Set<String>
    ) {
        val newPathSet = newIndex.mapTo(mutableSetOf()) { it.relativePath }
        for (old in oldIndex) {
            if (old.relativePath in newPathSet) continue
            // 已同步文件消失由 Delete 变更传播删除，不在此撤销
            if (old.status == FileSyncStatus.SYNCED && old.remoteFileId != null) continue
            if (old.relativePath in coveredPaths) continue
            taskDao.deleteUploadTasksForLocalPath(spaceId, mapping.id, old.relativePath)
        }
    }

    /** 冲突解决「保留本地」：以指定 baseVersion/fileId 提交 file_updated，覆盖云端内容。 */
    suspend fun enqueueOverwriteUpload(
        spaceId: String,
        mapping: LocalFolderMapping,
        localPath: String,
        size: Long,
        sha256: String?,
        baseVersion: Int,
        fileId: String,
        now: Long
    ) = enqueueUploadTask(spaceId, mapping, localPath, size, sha256, baseVersion, fileId, now)

    /**
     * 文件列表失败处理「保留云端」：按云端对象直接补建下载任务（无事件游标约束）。
     */
    suspend fun enqueueFileDownloadTask(
        spaceId: String,
        mappingId: String,
        localPath: String,
        fileId: String,
        objectId: String,
        size: Long,
        sha256: String,
        baseVersion: Int? = null
    ) {
        val remotePath = RemoteDownloadPlanner.remotePath(localPath)
        if (taskDao.countActiveTasks(spaceId, mappingId, remotePath) > 0) return
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
            baseVersion = baseVersion,
            operationId = "",
            createdAt = now,
            updatedAt = now
        ).let { it.copy(operationId = it.id) }
        taskDao.insertIfAbsent(task)
    }

    /** 文件列表失败处理「删除」：提交云端删除（产生 file_deleted 事件，其他设备同步移除）。 */
    suspend fun enqueueFileDeleteTask(
        spaceId: String,
        mapping: LocalFolderMapping,
        localPath: String,
        fileId: String,
        baseVersion: Int,
        size: Long
    ) = enqueueMutationTask(
        spaceId, mapping, SyncTaskType.DELETE,
        localPath = localPath, remotePath = localPath,
        fileId = fileId, baseVersion = baseVersion,
        size = size, oldLocalRelativePath = null, now = System.currentTimeMillis()
    )

    /** 文件失败处理「保留本地」（云端无对应文件）：按新文件上传。 */
    suspend fun enqueueFileUploadNew(
        spaceId: String,
        mapping: LocalFolderMapping,
        localPath: String,
        size: Long,
        sha256: String?
    ) = enqueueUploadTask(
        spaceId, mapping, localPath, size, sha256,
        baseVersion = null, fileId = null, now = System.currentTimeMillis()
    )

    suspend fun upsertIndex(index: LocalFileIndex) = fileDao.upsert(index)

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
        size: Long,
        oldLocalRelativePath: String?,
        now: Long,
        confirmed: Boolean = false
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
            size = size,
            baseVersion = baseVersion,
            oldLocalRelativePath = oldLocalRelativePath,
            confirmed = confirmed,
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
        val mapping = mappingDao.getById(mappingId) ?: return
        database.withTransaction {
            val localFiles = fileDao.getByMapping(mappingId)
            if (localFiles.isEmpty()) return@withTransaction
            val remoteByPath = remoteFiles.associateBy { it.relativePath }
            for (local in localFiles) {
                // 已关联且状态正常的文件无需重复核对；失败行即使已关联也要核对，用于修复历史失败
                if (!local.remoteFileId.isNullOrBlank() && local.status == FileSyncStatus.SYNCED) continue
                val remote = remoteByPath[local.relativePath] ?: continue
                val localSha = local.sha256
                val remoteSha = remote.sha256
                if (local.size != remote.size) continue
                // 扫描器对超大文件跳过哈希（sha 为空），此时只能按路径 + 大小认定同一文件；
                // 否则重装/重绑后这些文件会被当成新文件重复上传，最终报「远端已存在同名文件」。
                if (!localSha.isNullOrBlank() && !remoteSha.isNullOrBlank() &&
                    !localSha.equals(remoteSha, ignoreCase = true)
                ) continue
                fileDao.upsert(
                    local.copy(
                        status = FileSyncStatus.SYNCED,
                        remoteFileId = remote.fileId,
                        remoteVersion = remote.version
                    )
                )
                // 清理该路径遗留的排队/失败任务，避免失败列表残留「远端已存在同名文件」
                taskDao.deleteActiveTasksForRemotePath(
                    mapping.spaceId,
                    mappingId,
                    RemoteDownloadPlanner.remotePath(local.relativePath)
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
     * 类型取事件对应语义（DELETE / RENAME），避免任务中心把远端删除显示成“下载”。
     */
    suspend fun getOrCreateEventMarkerTask(
        spaceId: String,
        mappingId: String,
        localPath: String,
        remotePath: String,
        fileId: String,
        revision: String,
        type: String,
        size: Long = 0L
    ): LocalSyncTask {
        taskForEvent(spaceId, revision)?.let { return it }
        val now = System.currentTimeMillis()
        val task = LocalSyncTask(
            id = UUID.randomUUID().toString(),
            spaceId = spaceId,
            mappingId = mappingId,
            type = type,
            state = SyncTaskState.PENDING,
            localRelativePath = localPath,
            remoteRelativePath = remotePath,
            fileId = fileId,
            size = size,
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
