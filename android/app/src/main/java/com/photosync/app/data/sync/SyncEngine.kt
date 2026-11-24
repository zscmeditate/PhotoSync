package com.photosync.app.data.sync

import androidx.documentfile.provider.DocumentFile
import com.photosync.app.data.local.FileSyncStatus
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSyncCursor
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.local.SyncTaskState
import com.photosync.app.data.local.SyncTaskType
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.FileOperationRequest
import com.photosync.app.data.remote.ScanManifestFile
import com.photosync.app.data.remote.ScanManifestRequest
import com.photosync.app.data.remote.SyncEventPayloadDto
import com.photosync.app.data.remote.SyncAckRequest
import com.photosync.app.data.remote.SyncEventDto
import com.photosync.app.data.remote.UploadCompleteRequest
import com.photosync.app.data.remote.UploadCreateRequest
import com.photosync.app.data.remote.UploadDto
import com.photosync.app.data.remote.apiCall
import com.photosync.app.data.remote.parseErrorMessage
import com.photosync.app.data.repository.SyncRepository
import com.photosync.app.data.remote.SyncLockRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.IOException

/** 一次同步运行的结果；RETRY 由 WorkManager 按退避策略再次执行。 */
enum class SyncRunResult { SUCCESS, RETRY, FAILURE }

/** 仅实现 Phase 3 的新增上传与 file_created 增量下载。 */
class SyncEngine(
    private val repository: SyncRepository,
    private val fileStore: SafFileStore
) {
    private val api = repository.apiClient.api

    /** 当前同步阶段描述（供 UI 按钮实时显示正在做什么），null 表示空闲。 */
    private val _phase = MutableStateFlow<String?>(null)
    val phase: StateFlow<String?> = _phase.asStateFlow()

    private fun setPhase(text: String) {
        _phase.value = text
    }

    /**
     * 全量同步入口：各设备以文件为颗粒度并行同步。
     * 远端版本号天然保证顺序：并发上传/下载同一路径时，只有版本匹配的设备能成功；
     * 真正的写冲突（两边同时修改）会在 Phase 5 以路径冲突形式暴露给用户解决。
     */
    suspend fun syncAll(): SyncRunResult {
        // 不加全局锁，各设备以文件为颗粒度并行同步。
        // 远端文件是幂等的：不同设备不会同时上传同一路径（上传前会校验远端是否已变更），
        // 同一文件的下载/上传也不会互相覆盖——写冲突在路径冲突处理里解决。
        try {
            var result = SyncRunResult.SUCCESS
            for (spaceId in repository.spaceIds()) {
                when (syncSpace(spaceId)) {
                    SyncRunResult.SUCCESS -> Unit
                    SyncRunResult.RETRY -> if (result == SyncRunResult.SUCCESS) result = SyncRunResult.RETRY
                    SyncRunResult.FAILURE -> result = SyncRunResult.FAILURE
                }
            }
            return result
        } finally {
            _phase.value = null
        }
    }

    /**
     * 仅执行队列中已存在的任务（下载/上传/变更），与完整同步严格区分：
     * 不做本地扫描、不规划新任务、不拉取远端事件，失败任务也不重排（由用户显式处理）。
     * 供「刷新」按钮使用，成本低、可频繁触发。
     */
    suspend fun runQueuedTasks(): SyncRunResult {
        val deviceId = repository.currentDeviceId() ?: return SyncRunResult.FAILURE
        try {
            var result = SyncRunResult.SUCCESS
            for (spaceId in repository.spaceIds()) {
                repository.recoverRunning(spaceId)
                var taskFailed = false
                while (true) {
                    val task = repository.nextQueuedDownload(spaceId) ?: break
                    setPhase("正在下载：${task.remoteRelativePath.substringAfterLast('/')}")
                    when (downloadStandaloneTask(spaceId, deviceId, task)) {
                        SyncRunResult.SUCCESS -> Unit
                        SyncRunResult.RETRY -> return SyncRunResult.RETRY
                        SyncRunResult.FAILURE -> taskFailed = true
                    }
                }
                while (true) {
                    val task = repository.nextPendingUpload(spaceId) ?: break
                    setPhase("正在上传：${task.remoteRelativePath.substringAfterLast('/')}")
                    when (upload(task)) {
                        SyncRunResult.SUCCESS -> Unit
                        SyncRunResult.RETRY -> return SyncRunResult.RETRY
                        SyncRunResult.FAILURE -> taskFailed = true
                    }
                }
                while (true) {
                    val task = repository.nextPendingMutation(spaceId) ?: break
                    setPhase("正在提交变更：${task.remoteRelativePath.substringAfterLast('/')}")
                    when (commitMutation(task)) {
                        SyncRunResult.SUCCESS -> Unit
                        SyncRunResult.RETRY -> return SyncRunResult.RETRY
                        SyncRunResult.FAILURE -> taskFailed = true
                    }
                }
                if (taskFailed && result == SyncRunResult.SUCCESS) result = SyncRunResult.FAILURE
            }
            return result
        } finally {
            _phase.value = null
        }
    }

    suspend fun syncSpace(spaceId: String): SyncRunResult {
        val deviceId = repository.currentDeviceId() ?: return SyncRunResult.FAILURE
        repository.recoverRunning(spaceId)
        repository.recoverScanning(spaceId)
        val mappings = repository.syncMappings(spaceId, deviceId)
        // 扫描/规划失败只影响该目录的本地变更规划；下载、上传等既有队列照常执行。
        // 否则一次扫描异常会中断整轮同步，队列中的下载任务永远不被执行。
        var planFailed = false
        var planRetry = false
        for (mapping in mappings) {
            setPhase("正在扫描本地变更：${mapping.localPathLabel.ifBlank { "本地目录" }}")
            try {
                val oldIndex = repository.files(mapping.id)
                when (val scan = repository.scanMapping(mapping.id)) {
                    is ApiResult.Failure -> {
                        planFailed = true
                        continue
                    }
                    is ApiResult.Success -> Unit
                }
                val newIndex = repository.files(mapping.id)
                // 新绑定/重新绑定的映射先与远端清单核对，已存在的相同文件直接建立关联，
                // 并以关联后的索引作为差异基线，避免把相同文件重复规划为上传。
                var baseline = oldIndex
                var current = newIndex
                val freshlyBound = oldIndex.isEmpty() && newIndex.isNotEmpty()
                if (freshlyBound) {
                    when (reconcileFreshMapping(mapping, newIndex)) {
                        SyncRunResult.RETRY -> {
                            planRetry = true
                            continue
                        }
                        else -> Unit
                    }
                    current = repository.files(mapping.id)
                    baseline = current.filter {
                        it.status == FileSyncStatus.SYNCED && !it.remoteFileId.isNullOrBlank()
                    }
                }
                repository.planAndEnqueueLocalChanges(spaceId, mapping, baseline, current)
                // 兜底补齐：云端存在、本地索引缺失且没有未完成任务的文件，补建下载任务。
                // 用于修复历史「云端优先」未生成下载任务造成的本地缺失（新建空目录不自动拉全量）。
                if (!freshlyBound && current.isNotEmpty()) {
                    when (reconcileMissingDownloads(mapping, current.map { it.relativePath }.toSet())) {
                        SyncRunResult.RETRY -> planRetry = true
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                planFailed = true
            }
        }

        var taskFailed = false
        // 失败处理「云端优先」补建的独立下载任务没有对应远端事件，需在此主动执行。
        while (true) {
            val task = repository.nextStandaloneDownload(spaceId) ?: break
            setPhase("正在下载：${task.remoteRelativePath.substringAfterLast('/')}")
            when (downloadStandaloneTask(spaceId, deviceId, task)) {
                SyncRunResult.SUCCESS -> Unit
                SyncRunResult.RETRY -> return SyncRunResult.RETRY
                SyncRunResult.FAILURE -> taskFailed = true
            }
        }
        while (true) {
            val task = repository.nextPendingUpload(spaceId) ?: break
            setPhase("正在上传：${task.remoteRelativePath.substringAfterLast('/')}")
            when (upload(task)) {
                SyncRunResult.SUCCESS -> Unit
                SyncRunResult.RETRY -> return SyncRunResult.RETRY
                SyncRunResult.FAILURE -> taskFailed = true
            }
        }
        while (true) {
            val task = repository.nextPendingMutation(spaceId) ?: break
            setPhase("正在提交变更：${task.remoteRelativePath.substringAfterLast('/')}")
            when (commitMutation(task)) {
                SyncRunResult.SUCCESS -> Unit
                SyncRunResult.RETRY -> return SyncRunResult.RETRY
                SyncRunResult.FAILURE -> taskFailed = true
            }
        }
        // 单个任务失败不应阻断本轮的远端变更拉取：先拉取，再在末尾汇报失败。
        val pulled = pullChanges(spaceId, deviceId, mappings)
        return when {
            pulled != SyncRunResult.SUCCESS -> pulled
            taskFailed || planFailed -> SyncRunResult.FAILURE
            planRetry -> SyncRunResult.RETRY
            else -> SyncRunResult.SUCCESS
        }
    }

    /**
     * 首次绑定（本地索引为空）时，用本地清单向服务端换取远端文件清单，
     * 把路径、大小、sha256 均一致的远端文件标记为已同步，避免重复上传。
     */
    private suspend fun reconcileFreshMapping(
        mapping: LocalFolderMapping,
        index: List<LocalFileIndex>
    ): SyncRunResult {
        if (index.size > MAX_MANIFEST_FILES) return SyncRunResult.SUCCESS
        val manifest = index.map { file ->
            ScanManifestFile(
                relativePath = file.relativePath,
                size = file.size,
                sha256 = file.sha256,
                modifiedAt = null
            )
        }
        return when (
            val result = apiCall {
                api.scanManifest(
                    mapping.spaceId,
                    ScanManifestRequest(
                        deviceId = mapping.deviceId,
                        mappingId = mapping.id,
                        scanComplete = true,
                        files = manifest
                    )
                )
            }
        ) {
            is ApiResult.Failure -> if (result.isNetwork) {
                SyncRunResult.RETRY
            } else {
                SyncRunResult.SUCCESS
            }
            is ApiResult.Success -> {
                repository.reconcileRemoteLinks(mapping.id, result.data.remoteFiles)
                SyncRunResult.SUCCESS
            }
        }
    }

    /**
     * 兜底补齐：云端存在、但本地索引缺失、且没有未完成任务的文件，补建独立下载任务。
     * 只依赖远端清单即可发现这类文件（正常情况由事件驱动下载覆盖）。
     */
    private suspend fun reconcileMissingDownloads(
        mapping: LocalFolderMapping,
        localPaths: Set<String>
    ): SyncRunResult {
        val remote = when (
            val result = apiCall {
                api.scanManifest(
                    mapping.spaceId,
                    ScanManifestRequest(
                        deviceId = mapping.deviceId,
                        mappingId = mapping.id,
                        scanComplete = true,
                        files = emptyList()
                    )
                )
            }
        ) {
            is ApiResult.Failure -> return if (result.isNetwork) SyncRunResult.RETRY else SyncRunResult.SUCCESS
            is ApiResult.Success -> result.data.remoteFiles
        }
        if (remote.isEmpty()) return SyncRunResult.SUCCESS
        val activePaths = repository.activeRemotePaths(mapping.spaceId).toHashSet()
        for (file in remote) {
            if (file.relativePath in localPaths || file.relativePath in activePaths) continue
            // 补齐下载需要 objectId：按路径取当前生效版本
            val current = when (
                val result = apiCall { api.getFileByPath(mapping.spaceId, mapping.deviceId, file.relativePath) }
            ) {
                is ApiResult.Failure -> return if (result.isNetwork) SyncRunResult.RETRY else SyncRunResult.SUCCESS
                is ApiResult.Success -> result.data.file ?: continue
            }
            if (current.objectId.isBlank() || current.sha256.isBlank()) continue
            repository.enqueueFileDownloadTask(
                spaceId = mapping.spaceId,
                mappingId = mapping.id,
                localPath = file.relativePath,
                fileId = file.fileId,
                objectId = current.objectId,
                size = current.size.toLongOrNull() ?: file.size,
                sha256 = current.sha256
            )
        }
        return SyncRunResult.SUCCESS
    }

    /**
     * 传输进度边界检查：任务行被删除（取消）或状态不再是 RUNNING（移到队列末尾）时中断当前任务。
     * 使用独立异常，避免把用户操作误当作协程取消而终止整轮同步。
     */
    private suspend fun ensureTaskRunning(taskId: String) {
        val row = repository.getTask(taskId) ?: throw TaskAbortedException()
        if (row.state != SyncTaskState.RUNNING) throw TaskAbortedException()
    }

    private suspend fun upload(task: LocalSyncTask): SyncRunResult {
        var current = repository.markTaskRunning(task)
        try {
            val mapping = repository.mapping(task.mappingId)
                ?: return failUpload(current, "MAPPING_NOT_FOUND", "本地目录映射不存在")
            val index = repository.file(mapping.id, task.localRelativePath)
                ?: return failUpload(current, "LOCAL_FILE_MISSING", "本地文件已不在索引中")
            val document = fileStore.openIndexedFile(index)
            val digest = fileStore.digest(document)
            if (digest.size != index.size ||
                (!index.sha256.isNullOrBlank() && !digest.sha256.equals(index.sha256, true))
            ) {
                return failUpload(current, "LOCAL_CONTENT_CHANGED", "文件在扫描后发生变化，请重新扫描")
            }
            val remotePath = RemoteDownloadPlanner.remotePath(index.relativePath)
            if (remotePath != task.remoteRelativePath) {
                return failUpload(current, "UPLOAD_PATH_CHANGED", "目录映射已变化，请重新扫描")
            }

            val upload = if (current.uploadId == null) {
                when (
                    val result = apiCall {
                        api.createUpload(
                            UploadCreateRequest(
                                spaceId = current.spaceId,
                                deviceId = mapping.deviceId,
                                relativePath = current.remoteRelativePath,
                                size = digest.size,
                                sha256 = digest.sha256,
                                partSize = PART_SIZE,
                                idempotencyKey = current.id
                            )
                        )
                    }
                ) {
                    is ApiResult.Failure -> return uploadRequestFailure(current, result)
                    is ApiResult.Success -> result.data.upload
                }
            } else {
                when (val result = apiCall { api.getUpload(current.uploadId!!, mapping.deviceId) }) {
                    is ApiResult.Failure -> return uploadRequestFailure(current, result)
                    is ApiResult.Success -> result.data.upload
                }
            }
            val session = validateUpload(upload, current, digest.size, digest.sha256)
            if (upload.status !in setOf("pending", "uploading", "completed")) {
                return failUpload(current, "UPLOAD_NOT_ACTIVE", "服务端上传会话状态为 ${upload.status}")
            }
            current = current.copy(
                uploadId = upload.uploadId,
                objectId = upload.objectId,
                size = digest.size,
                sha256 = digest.sha256,
                uploadedParts = session.parts.size,
                bytesTransferred = session.bytesTransferred,
                nextPartNumber = session.firstMissingPart,
                updatedAt = System.currentTimeMillis()
            )
            repository.saveTask(current)

            val uploaded = session.parts.map { it.partNumber }.toMutableSet()
            for (partNumber in 1..session.partCount) {
                // 任务被取消（行已删除）或被移到队列末尾（状态不再是 RUNNING）：中断当前传输
                ensureTaskRunning(current.id)
                if (partNumber in uploaded) continue
                val length = expectedPartSize(session.size, session.partSize, partNumber)
                val offset = (partNumber - 1).toLong() * session.partSize
                when (
                    val result = apiCall {
                        api.uploadPart(
                            uploadId = upload.uploadId,
                            partNumber = partNumber,
                            deviceId = mapping.deviceId,
                            idempotencyKey = "${current.id}:part:$partNumber",
                            body = fileStore.partRequestBody(document, offset, length)
                        )
                    }
                ) {
                    is ApiResult.Failure -> return uploadRequestFailure(current, result)
                    is ApiResult.Success -> {
                        val part = result.data.part
                        if (part.partNumber != partNumber ||
                            DecimalContract.long(part.size, "part.size", false) != length
                        ) {
                            return failUpload(current, "UPLOAD_RESPONSE_INVALID", "服务端分片响应不一致")
                        }
                        DecimalContract.requireSha256(part.sha256)
                    }
                }
                uploaded += partNumber
                current = current.copy(
                    uploadedParts = uploaded.size,
                    bytesTransferred = current.bytesTransferred + length,
                    nextPartNumber = (1..session.partCount).firstOrNull { it !in uploaded }
                        ?: (session.partCount + 1),
                    updatedAt = System.currentTimeMillis()
                )
                repository.updateUploadProgress(
                    current.id,
                    current.uploadedParts,
                    current.bytesTransferred,
                    current.nextPartNumber
                )
            }

            val completed = when (
                val result = apiCall {
                    api.completeUpload(
                        upload.uploadId,
                        UploadCompleteRequest(mapping.deviceId, current.id)
                    )
                }
            ) {
                is ApiResult.Failure -> return uploadRequestFailure(current, result)
                is ApiResult.Success -> result.data.upload
            }
            validateUpload(completed, current, digest.size, digest.sha256)
            if (completed.status != "completed") {
                return failUpload(current, "UPLOAD_INCOMPLETE", "服务端尚未完成上传合并")
            }

            val isModify = current.baseVersion != null && current.baseVersion >= 1 &&
                !current.fileId.isNullOrBlank()
            val operationType = if (isModify) "file_updated" else "file_created"
            val operation = when (
                val result = apiCall {
                    api.createFileOperation(
                        current.spaceId,
                        FileOperationRequest(
                            deviceId = mapping.deviceId,
                            operationId = current.id,
                            type = operationType,
                            uploadId = upload.uploadId,
                            fileId = if (isModify) current.fileId else null,
                            baseVersion = current.baseVersion ?: 0,
                            newRelativePath = null
                        )
                    )
                }
            ) {
                is ApiResult.Failure -> return uploadRequestFailure(current, result)
                is ApiResult.Success -> result.data
            }
            val remote = operation.file
            if (remote.fileId.isBlank() || remote.relativePath != current.remoteRelativePath ||
                remote.objectId != completed.objectId ||
                DecimalContract.long(remote.size, "file.size") != digest.size ||
                DecimalContract.requireSha256(remote.sha256) != digest.sha256 ||
                remote.version <= 0 ||
                (isModify && remote.fileId != current.fileId)
            ) {
                return failUpload(current, "OPERATION_RESPONSE_INVALID", "服务端文件操作响应不一致")
            }
            DecimalContract.revision(operation.event.revision)
            if (operation.event.type != operationType) {
                return failUpload(current, "OPERATION_RESPONSE_INVALID", "服务端事件类型不正确")
            }
            repository.markSynced(
                current,
                index.copy(
                    size = digest.size,
                    sha256 = digest.sha256,
                    remoteFileId = remote.fileId,
                    remoteVersion = remote.version
                )
            )
            return SyncRunResult.SUCCESS
        } catch (e: TaskAbortedException) {
            // 用户取消或移到队列末尾：任务行已被删除或重置为 PENDING，本次不再写入状态
            return SyncRunResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: FolderAccessException) {
            return failUpload(current, "ACCESS_REVOKED", e.message ?: "本地目录授权已失效")
        } catch (e: SyncPlanningException) {
            return failUpload(current, "SYNC_CONTRACT_INVALID", e.message ?: "同步数据无效")
        } catch (e: ContentVerificationException) {
            return failUpload(current, "CONTENT_VERIFICATION", e.message ?: "文件校验失败")
        } catch (e: SafFileException) {
            return failUpload(current, "LOCAL_FILE_ERROR", e.message ?: "读取本地文件失败")
        } catch (e: Exception) {
            return failUpload(current, "SYNC_ERROR", e.message ?: "上传失败")
        }
    }

    private suspend fun commitMutation(task: LocalSyncTask): SyncRunResult {
        val current = repository.markTaskRunning(task)
        try {
            val mapping = repository.mapping(task.mappingId)
                ?: return failUpload(current, "MAPPING_NOT_FOUND", "本地目录映射不存在")
            val fileId = current.fileId
            val baseVersion = current.baseVersion ?: 1
            if (fileId.isNullOrBlank()) {
                return failUpload(current, "MUTATION_INVALID", "变异任务缺少 fileId")
            }
            val operationType = when (current.type) {
                SyncTaskType.DELETE -> "file_deleted"
                SyncTaskType.RENAME -> "file_renamed"
                else -> return failUpload(current, "MUTATION_UNSUPPORTED", "不支持的任务类型 ${current.type}")
            }
            val operation = when (val result = apiCall {
                api.createFileOperation(
                    current.spaceId,
                    FileOperationRequest(
                        deviceId = mapping.deviceId,
                        operationId = current.id,
                        type = operationType,
                        uploadId = null,
                        fileId = fileId,
                        baseVersion = baseVersion,
                        newRelativePath = if (current.type == SyncTaskType.RENAME) current.remoteRelativePath else null
                    )
                )
            }) {
                is ApiResult.Failure -> {
                    // 幂等：远端已删除则视为成功
                    if (current.type == SyncTaskType.DELETE && result.code == "FILE_IS_DELETED") {
                        repository.deleteIndex(mapping.id, current.localRelativePath)
                        repository.completeTask(current)
                        return SyncRunResult.SUCCESS
                    }
                    return mutationRequestFailure(current, result)
                }
                is ApiResult.Success -> result.data
            }
            DecimalContract.revision(operation.event.revision)
            if (operation.event.type != operationType) {
                return failUpload(current, "OPERATION_RESPONSE_INVALID", "服务端事件类型不正确")
            }
            when (current.type) {
                SyncTaskType.DELETE -> {
                    repository.deleteIndex(mapping.id, current.localRelativePath)
                    repository.completeTask(current)
                }
                SyncTaskType.RENAME -> {
                    val target = repository.file(mapping.id, current.localRelativePath)
                    if (target != null) {
                        repository.markSynced(
                            current,
                            target.copy(
                                remoteFileId = operation.file.fileId,
                                remoteVersion = operation.file.version
                            )
                        )
                    } else {
                        repository.completeTask(current)
                    }
                }
                else -> return failUpload(current, "MUTATION_UNSUPPORTED", "不支持的任务类型 ${current.type}")
            }
            return SyncRunResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncPlanningException) {
            return failUpload(current, "SYNC_CONTRACT_INVALID", e.message ?: "同步数据无效")
        } catch (e: Exception) {
            return failUpload(current, "SYNC_ERROR", e.message ?: "变异提交失败")
        }
    }

    private suspend fun mutationRequestFailure(
        task: LocalSyncTask,
        failure: ApiResult.Failure
    ): SyncRunResult {
        if (failure.isNetwork) {
            repository.markTaskRetry(task, "NETWORK_ERROR", failure.message)
            return SyncRunResult.RETRY
        }
        return failUpload(task, failure.code ?: "MUTATION_REQUEST_FAILED", failure.message)
    }

    private suspend fun pullChanges(
        spaceId: String,
        deviceId: String,
        mappings: List<com.photosync.app.data.local.LocalFolderMapping>
    ): SyncRunResult {
        var since = repository.cursor(spaceId, deviceId)?.lastAppliedRevision ?: "0"
        setPhase("正在获取云端变更…")
        try {
            DecimalContract.revision(since)
            val rules = mappings.map {
                SyncMappingRule(it.id, it.onlyMedia)
            }
            // 阶段一：先拉取全部远端事件（只读、不确认），逐页校验游标契约。
            val events = mutableListOf<SyncEventDto>()
            var previous = since
            while (true) {
                val changes = when (
                    val result = apiCall { api.getChanges(spaceId, deviceId, since, PAGE_SIZE) }
                ) {
                    is ApiResult.Failure -> return if (result.isNetwork) {
                        SyncRunResult.RETRY
                    } else {
                        SyncRunResult.FAILURE
                    }
                    is ApiResult.Success -> result.data
                }
                DecimalContract.revision(changes.serverRevision)
                DecimalContract.revision(changes.nextRevision)
                if (changes.events.isEmpty()) {
                    if (events.isEmpty()) {
                        if (changes.hasMore ||
                            DecimalContract.compareRevisions(changes.nextRevision, since) != 0
                        ) {
                            return SyncRunResult.FAILURE
                        }
                        repository.saveCursor(
                            LocalSyncCursor(
                                spaceId,
                                deviceId,
                                since,
                                changes.serverRevision,
                                System.currentTimeMillis()
                            )
                        )
                        return SyncRunResult.SUCCESS
                    }
                    // 尾页为空：已收集的事件进入处理阶段，游标随逐条确认推进。
                    if (changes.hasMore ||
                        DecimalContract.compareRevisions(changes.nextRevision, previous) != 0
                    ) {
                        return SyncRunResult.FAILURE
                    }
                    break
                }
                for (event in changes.events) {
                    val revision = DecimalContract.revision(event.revision)
                    if (DecimalContract.compareRevisions(revision, previous) <= 0) {
                        return SyncRunResult.FAILURE
                    }
                    events.add(event)
                    previous = revision
                }
                if (DecimalContract.compareRevisions(changes.nextRevision, previous) != 0) {
                    return SyncRunResult.FAILURE
                }
                if (!changes.hasMore) break
                since = previous
            }

            // 阶段二：先为所有对象事件预建持久化下载任务（不执行），
            // 任务中心因此能完整列出待下载清单与剩余数量。
            for (event in events) {
                val payload = event.payload ?: continue
                if (event.type in OBJECT_EVENT_TYPES) {
                    planObjectEventTask(spaceId, deviceId, rules, event, payload)
                }
            }

            // 阶段三：逐个处理并确认；游标随确认推进，失败时保持不动（下次重拉重试）。
            for (event in events) {
                val revision = DecimalContract.revision(event.revision)
                val phaseName = event.payload?.newFileName
                    ?: event.payload?.fileName
                    ?: event.payload?.newPath
                    ?: event.payload?.relativePath
                if (phaseName.isNullOrBlank()) {
                    setPhase("正在应用云端变更…")
                } else {
                    setPhase("正在应用云端变更：$phaseName")
                }
                when (processEvent(spaceId, deviceId, rules, event)) {
                    SyncRunResult.SUCCESS -> Unit
                    SyncRunResult.RETRY -> return SyncRunResult.RETRY
                    SyncRunResult.FAILURE -> return SyncRunResult.FAILURE
                }
                val ack = when (
                    val result = apiCall {
                        api.acknowledgeChanges(spaceId, SyncAckRequest(deviceId, revision))
                    }
                ) {
                    is ApiResult.Failure -> return if (result.isNetwork) {
                        SyncRunResult.RETRY
                    } else {
                        SyncRunResult.FAILURE
                    }
                    is ApiResult.Success -> result.data
                }
                DecimalContract.revision(ack.lastAppliedRevision)
                DecimalContract.revision(ack.serverRevision)
                if (DecimalContract.compareRevisions(ack.lastAppliedRevision, revision) != 0) {
                    return SyncRunResult.FAILURE
                }
                repository.saveCursor(
                    LocalSyncCursor(
                        spaceId,
                        deviceId,
                        revision,
                        ack.serverRevision,
                        System.currentTimeMillis()
                    )
                )
            }
            return SyncRunResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncRunResult.FAILURE
        }
    }

    /**
     * 规划阶段：为对象事件预建持久化下载任务，只落库不执行任何副作用。
     * 跳过条件与 applyObjectEvent 的任务创建前检查保持一致；执行阶段通过
     * (spaceId, eventRevision) 唯一约束复用同一任务，不会重复建任务。
     */
    private suspend fun planObjectEventTask(
        spaceId: String,
        deviceId: String,
        mappings: List<SyncMappingRule>,
        event: SyncEventDto,
        payload: SyncEventPayloadDto
    ) {
        try {
            val sizeRaw = payload.size ?: return
            val shaRaw = payload.sha256 ?: return
            if (payload.fileId.isNullOrBlank() || payload.objectId.isNullOrBlank() ||
                (payload.version ?: 0) <= 0
            ) {
                return
            }
            val expectedSha256 = DecimalContract.requireSha256(shaRaw)
            if (repository.taskForEvent(spaceId, event.revision) != null) return
            if (mappings.isEmpty()) return
            val plan = RemoteDownloadPlanner.plan(payload.relativePath ?: "", mappings) ?: return
            val mapping = repository.mapping(plan.mappingId)
            if (mapping == null || mapping.spaceId != spaceId || mapping.status != MappingStatus.ACTIVE) {
                return
            }
            val oldIndex = repository.file(mapping.id, plan.localRelativePath)
            if (event.type == "file_updated" && event.deviceId == deviceId) return
            if (SyncEventPlanner.shouldSkipOwnEvent(
                    event.deviceId, deviceId,
                    oldIndex?.remoteFileId, oldIndex?.sha256,
                    payload.fileId, expectedSha256
                )
            ) {
                return
            }
            repository.getOrCreateDownloadTask(
                spaceId = spaceId,
                mappingId = mapping.id,
                localPath = plan.localRelativePath,
                remotePath = plan.remoteRelativePath,
                fileId = payload.fileId,
                objectId = payload.objectId,
                size = DecimalContract.long(sizeRaw, "event.payload.size"),
                sha256 = expectedSha256,
                revision = event.revision
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 预建失败不阻断同步：执行阶段会按原逻辑重建任务并正确上报错误。
        }
    }

    private suspend fun processEvent(
        spaceId: String,
        deviceId: String,
        mappings: List<SyncMappingRule>,
        event: SyncEventDto
    ): SyncRunResult {
        val payload = event.payload ?: return SyncRunResult.FAILURE
        // 本机尚未绑定任何目录时不确认事件：否则远端已有文件会被当作基线吞掉，
        // 绑定目录后也无法补下载（例如从设备加入已有空间后尚未选择本地目录）。
        if (mappings.isEmpty()) return SyncRunResult.RETRY
        return when (event.type) {
            // file_restored 与新建/修改一样需要把对象下载回本地。
            "file_created", "file_updated", "file_restored" ->
                applyObjectEvent(spaceId, deviceId, mappings, event, payload)
            "file_deleted" -> applyRemoteDelete(spaceId, deviceId, mappings, event, payload)
            "file_renamed" -> applyRemoteRename(spaceId, deviceId, mappings, event, payload)
            else -> SyncRunResult.FAILURE
        }
    }

    /** 处理 file_created / file_updated：下载对象并写入索引（file_updated 覆盖旧版本）。 */
    private suspend fun applyObjectEvent(
        spaceId: String,
        deviceId: String,
        mappings: List<SyncMappingRule>,
        event: SyncEventDto,
        payload: SyncEventPayloadDto
    ): SyncRunResult {
        try {
            val sizeRaw = payload.size ?: return SyncRunResult.FAILURE
            val shaRaw = payload.sha256 ?: return SyncRunResult.FAILURE
            if (payload.fileId.isNullOrBlank() || payload.objectId.isNullOrBlank() ||
                (payload.version ?: 0) <= 0
            ) {
                return SyncRunResult.FAILURE
            }
            val expectedSize = DecimalContract.long(sizeRaw, "event.payload.size")
            val expectedSha256 = DecimalContract.requireSha256(shaRaw)
            // 已落库的失败任务始终阻塞游标，即使映射随后被停用也不能跳过该事件。
            // 例外：对象已在服务端永久丢失时无法重试，跳过该事件以免阻塞整轮同步（任务记录保留）。
            val existingTask = repository.taskForEvent(spaceId, event.revision)
            if (existingTask?.state == SyncTaskState.FAILED) {
                return if (isObjectMissing(existingTask.error)) {
                    SyncRunResult.SUCCESS
                } else {
                    SyncRunResult.FAILURE
                }
            }
            if (existingTask?.state == SyncTaskState.COMPLETED) return SyncRunResult.SUCCESS
            val plan = RemoteDownloadPlanner.plan(payload.relativePath ?: "", mappings)
                ?: return if (existingTask == null) SyncRunResult.SUCCESS else SyncRunResult.FAILURE
            val mapping = repository.mapping(plan.mappingId)
            if (mapping == null || mapping.spaceId != spaceId || mapping.status != MappingStatus.ACTIVE) {
                return SyncRunResult.FAILURE
            }
            val oldIndex = repository.file(mapping.id, plan.localRelativePath)
            if (event.type == "file_updated") {
                // 本设备发起的修改无需再应用；file_created 仍按内容判断避免重复下载。
                if (event.deviceId == deviceId) {
                    settleSkippedEventTask(spaceId, event.revision)
                    return SyncRunResult.SUCCESS
                }
            } else if (SyncEventPlanner.shouldSkipOwnEvent(
                    event.deviceId, deviceId,
                    oldIndex?.remoteFileId, oldIndex?.sha256,
                    payload.fileId, expectedSha256
                )
            ) {
                settleSkippedEventTask(spaceId, event.revision)
                return SyncRunResult.SUCCESS
            }
            // 冲突前置：file_updated 时若本地内容已偏离上次同步版本，先把本地版本保留为冲突副本，
            // 再下载远端版本覆盖原路径（不静默丢弃任何一方）。
            if (event.type == "file_updated" && isLocalDiverged(mapping, oldIndex)) {
                preserveLocalAsConflictCopy(mapping, oldIndex!!)
                fileStore.delete(mapping, plan.localRelativePath)
            }

            var task = repository.getOrCreateDownloadTask(
                spaceId = spaceId,
                mappingId = mapping.id,
                localPath = plan.localRelativePath,
                remotePath = plan.remoteRelativePath,
                fileId = payload.fileId,
                objectId = payload.objectId,
                size = expectedSize,
                sha256 = expectedSha256,
                revision = event.revision
            )
            if (task.state == SyncTaskState.COMPLETED) return SyncRunResult.SUCCESS
            if (task.state == SyncTaskState.FAILED) return SyncRunResult.FAILURE
            task = repository.markTaskRunning(task)

            val target = fileStore.findTarget(mapping, plan.localRelativePath)
            if (target != null) {
                if (!target.isFile || !target.canRead()) {
                    val conflict = oldIndex ?: localIndex(
                        mapping.id, plan.localRelativePath, target,
                        target.length().coerceAtLeast(0L), oldIndex?.sha256, payload, null
                    )
                    return failDownload(task, "LOCAL_PATH_CONFLICT", "目标路径不是可读文件", conflict)
                }
                val digest = fileStore.digest(target)
                // 远端更新的是本设备上次已同步的同一文件、且本地仍保持上次同步内容时，
                // 直接覆盖旧版本；否则每次远端修改都会被误判为本地冲突。
                val replacingSyncedVersion = event.type == "file_updated" &&
                    oldIndex != null &&
                    oldIndex.remoteFileId == payload.fileId &&
                    !oldIndex.sha256.isNullOrBlank() &&
                    digest.sha256.equals(oldIndex.sha256, true)
                if (replacingSyncedVersion) {
                    val removed = fileStore.delete(mapping, plan.localRelativePath)
                    if (!removed) {
                        val conflict = localIndex(
                            mapping.id, plan.localRelativePath, target,
                            digest.size, digest.sha256, payload, oldIndex
                        )
                        return failDownload(task, "LOCAL_FILE_ERROR", "无法移除旧版本文件", conflict)
                    }
                } else {
                    when (ExistingLocalPlanner.decide(
                        targetExists = true,
                        sameSize = digest.size == expectedSize,
                        sameSha256 = digest.sha256.equals(expectedSha256, true)
                    )) {
                        ExistingLocalDecision.INDEX_ONLY -> {
                            repository.markSynced(
                                task,
                                localIndex(mapping.id, plan.localRelativePath, target, digest.size, digest.sha256, payload, oldIndex)
                            )
                            return SyncRunResult.SUCCESS
                        }
                        ExistingLocalDecision.CONFLICT -> {
                            val conflict = localIndex(mapping.id, plan.localRelativePath, target, digest.size, digest.sha256, payload, oldIndex)
                            return failDownload(task, "LOCAL_PATH_CONFLICT", "本地已有不同内容，未覆盖远端或本地文件", conflict)
                        }
                        ExistingLocalDecision.DOWNLOAD -> Unit
                    }
                }
            }

            try {
                val downloaded = fileStore.downloadVerified(
                    mapping = mapping,
                    localRelativePath = plan.localRelativePath,
                    taskId = task.id,
                    fetch = { rangeStart ->
                        downloadChunk(payload.objectId, deviceId, rangeStart)
                    },
                    expectedSize = expectedSize,
                    expectedSha256 = expectedSha256,
                    onProgress = { transferred ->
                        // 任务被取消或被移到队列末尾：中断当前下载
                        ensureTaskRunning(task.id)
                        repository.updateDownloadProgress(task.id, transferred)
                    }
                )
                repository.markSynced(
                    task,
                    localIndex(mapping.id, plan.localRelativePath, downloaded, expectedSize, expectedSha256, payload, oldIndex)
                )
            } catch (e: DownloadRequestFailureException) {
                return downloadRequestFailure(task, e.failure)
            }
            return SyncRunResult.SUCCESS
        } catch (e: TaskAbortedException) {
            // 下载被取消/移到队列末尾：事件不确认，下次同步会重新拉取并在队列末尾重试
            return SyncRunResult.FAILURE
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalPathConflictException) {
            val task = repository.taskForEvent(spaceId, event.revision)
            return if (task == null) SyncRunResult.FAILURE else {
                repository.markTaskFailed(task, "LOCAL_PATH_CONFLICT", e.message ?: "本地路径冲突")
                SyncRunResult.FAILURE
            }
        } catch (e: IOException) {
            val task = repository.taskForEvent(spaceId, event.revision)
            return if (task != null && e !is SafFileException) {
                repository.markTaskRetry(task, "NETWORK_ERROR", e.message ?: "下载中断")
                SyncRunResult.RETRY
            } else {
                if (task != null) repository.markTaskFailed(task, "LOCAL_FILE_ERROR", e.message ?: "本地文件失败")
                SyncRunResult.FAILURE
            }
        } catch (e: FolderAccessException) {
            return failEventTask(spaceId, event.revision, "ACCESS_REVOKED", e.message ?: "目录授权失效")
        } catch (e: ContentVerificationException) {
            return failEventTask(spaceId, event.revision, "CONTENT_VERIFICATION", e.message ?: "下载校验失败")
        } catch (e: SyncPlanningException) {
            return failEventTask(spaceId, event.revision, "SYNC_CONTRACT_INVALID", e.message ?: "同步数据无效")
        } catch (e: Exception) {
            return failEventTask(spaceId, event.revision, "SYNC_ERROR", e.message ?: "下载失败")
        }
    }

    /**
     * 执行不依赖远端事件的独立下载任务（失败处理「云端优先」补建）。
     * 目标已存在且内容一致时只补索引；内容不一致时标记冲突，交给用户处理。
     */
    private suspend fun downloadStandaloneTask(
        spaceId: String,
        deviceId: String,
        task: LocalSyncTask
    ): SyncRunResult {
        val mapping = repository.mapping(task.mappingId)
        if (mapping == null || mapping.spaceId != spaceId) {
            repository.markTaskFailed(task, "MAPPING_NOT_FOUND", "本地目录映射已不存在，请重新绑定")
            return SyncRunResult.FAILURE
        }
        if (mapping.status != MappingStatus.ACTIVE && mapping.status != MappingStatus.ERROR) {
            // 目录正在扫描或授权失效：保留任务，下次同步继续，不判失败。
            // ERROR 映射仍可下载：扫描失败只阻塞本地变更规划，不阻塞写入云端文件。
            repository.markTaskRetry(task, "MAPPING_BUSY", "本地目录正在扫描或暂不可用，稍后自动重试")
            return SyncRunResult.SUCCESS
        }
        var current = repository.markTaskRunning(task)
        val objectId = current.objectId
        val fileId = current.fileId
        val expectedSha256 = current.sha256
        if (objectId.isNullOrBlank() || fileId.isNullOrBlank() || expectedSha256.isNullOrBlank()) {
            repository.markTaskFailed(current, "SYNC_CONTRACT_INVALID", "下载任务缺少云端对象信息，无法执行")
            return SyncRunResult.FAILURE
        }
        val target = fileStore.findTarget(mapping, current.localRelativePath)
        if (target != null && target.isFile && target.canRead()) {
            val digest = fileStore.digest(target)
            if (digest.size == current.size && digest.sha256.equals(expectedSha256, true)) {
                repository.markSynced(
                    current,
                    LocalFileIndex(
                        id = 0,
                        mappingId = mapping.id,
                        relativePath = current.localRelativePath,
                        fileName = target.name ?: current.localRelativePath.substringAfterLast('/'),
                        size = digest.size,
                        modifiedAt = target.lastModified(),
                        sha256 = digest.sha256,
                        docUri = target.uri.toString(),
                        status = FileSyncStatus.SYNCED,
                        lastScannedAt = System.currentTimeMillis(),
                        remoteFileId = fileId,
                        remoteVersion = current.baseVersion
                    )
                )
                return SyncRunResult.SUCCESS
            }
            val conflict = LocalFileIndex(
                id = 0,
                mappingId = mapping.id,
                relativePath = current.localRelativePath,
                fileName = target.name ?: current.localRelativePath.substringAfterLast('/'),
                size = digest.size,
                modifiedAt = target.lastModified(),
                sha256 = digest.sha256,
                docUri = target.uri.toString(),
                status = FileSyncStatus.CONFLICT,
                lastScannedAt = System.currentTimeMillis(),
                remoteFileId = fileId,
                remoteVersion = current.baseVersion
            )
            return failDownload(current, "LOCAL_PATH_CONFLICT", "本地已有不同内容，未覆盖远端或本地文件", conflict)
        }
        try {
            val downloaded = fileStore.downloadVerified(
                mapping = mapping,
                localRelativePath = current.localRelativePath,
                taskId = current.id,
                fetch = { rangeStart -> downloadChunk(objectId, deviceId, rangeStart) },
                expectedSize = current.size,
                expectedSha256 = expectedSha256,
                onProgress = { transferred ->
                    // 任务被取消或被移到队列末尾：中断当前下载
                    ensureTaskRunning(current.id)
                    repository.updateDownloadProgress(current.id, transferred)
                }
            )
            repository.markSynced(
                current,
                LocalFileIndex(
                    id = 0,
                    mappingId = mapping.id,
                    relativePath = current.localRelativePath,
                    fileName = downloaded.name ?: current.localRelativePath.substringAfterLast('/'),
                    size = downloaded.length(),
                    modifiedAt = downloaded.lastModified(),
                    sha256 = expectedSha256,
                    docUri = downloaded.uri.toString(),
                    status = FileSyncStatus.SYNCED,
                    lastScannedAt = System.currentTimeMillis(),
                    remoteFileId = fileId,
                    remoteVersion = current.baseVersion
                )
            )
            return SyncRunResult.SUCCESS
        } catch (e: TaskAbortedException) {
            // 队列末尾：任务行已重置为 PENDING，下一轮（或本轮其他任务之后）继续
            return SyncRunResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadRequestFailureException) {
            return downloadRequestFailure(current, e.failure)
        } catch (e: LocalPathConflictException) {
            repository.markTaskFailed(current, "LOCAL_PATH_CONFLICT", e.message ?: "本地路径冲突")
            return SyncRunResult.FAILURE
        } catch (e: IOException) {
            return if (e !is SafFileException) {
                repository.markTaskRetry(current, "NETWORK_ERROR", e.message ?: "下载中断")
                SyncRunResult.RETRY
            } else {
                repository.markTaskFailed(current, "LOCAL_FILE_ERROR", e.message ?: "本地文件失败")
                SyncRunResult.FAILURE
            }
        } catch (e: ContentVerificationException) {
            repository.markTaskFailed(current, "CONTENT_VERIFICATION", e.message ?: "下载校验失败")
            return SyncRunResult.FAILURE
        } catch (e: Exception) {
            repository.markTaskFailed(current, "SYNC_ERROR", e.message ?: "下载失败")
            return SyncRunResult.FAILURE
        }
    }

    /** 处理 file_deleted：删除本地文件与索引；本地已修改则保留并阻塞（冲突解决留 Phase 5）。 */
    private suspend fun applyRemoteDelete(
        spaceId: String,
        deviceId: String,
        mappings: List<SyncMappingRule>,
        event: SyncEventDto,
        payload: SyncEventPayloadDto
    ): SyncRunResult {
        try {
            if (payload.fileId.isNullOrBlank()) return SyncRunResult.FAILURE
            if (event.deviceId == deviceId) return SyncRunResult.SUCCESS
            val existingTask = repository.taskForEvent(spaceId, event.revision)
            if (existingTask?.state == SyncTaskState.COMPLETED) return SyncRunResult.SUCCESS
            if (existingTask?.state == SyncTaskState.FAILED) return SyncRunResult.FAILURE
            val relativePath = payload.relativePath ?: return SyncRunResult.FAILURE
            val plan = RemoteDownloadPlanner.plan(relativePath, mappings)
                ?: return SyncRunResult.SUCCESS
            val mapping = repository.mapping(plan.mappingId)
            if (mapping == null || mapping.spaceId != spaceId || mapping.status != MappingStatus.ACTIVE) {
                return SyncRunResult.SUCCESS
            }
            val oldIndex = repository.file(mapping.id, plan.localRelativePath)
            if (oldIndex == null || oldIndex.remoteFileId != payload.fileId) {
                return SyncRunResult.SUCCESS
            }
            // 本地已修改但远端要删除：先把本地版本保留为冲突副本并重新上传，再执行远端删除。
            if (isLocalDiverged(mapping, oldIndex)) {
                preserveLocalAsConflictCopy(mapping, oldIndex)
            }
            fileStore.moveToTrash(mapping, plan.localRelativePath)
            repository.deleteIndex(mapping.id, plan.localRelativePath)
            repository.getOrCreateEventMarkerTask(
                spaceId, mapping.id, plan.localRelativePath, plan.remoteRelativePath,
                payload.fileId, event.revision
            ).let { repository.completeTask(it) }
            return SyncRunResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: FolderAccessException) {
            return failEventTask(spaceId, event.revision, "ACCESS_REVOKED", e.message ?: "目录授权失效")
        } catch (e: Exception) {
            return failEventTask(spaceId, event.revision, "SYNC_ERROR", e.message ?: "应用远端删除失败")
        }
    }

    /** 处理 file_renamed：移动本地文件并迁移索引；跨映射或本地已修改则阻塞（留 Phase 5）。 */
    private suspend fun applyRemoteRename(
        spaceId: String,
        deviceId: String,
        mappings: List<SyncMappingRule>,
        event: SyncEventDto,
        payload: SyncEventPayloadDto
    ): SyncRunResult {
        try {
            if (payload.fileId.isNullOrBlank()) return SyncRunResult.FAILURE
            if (event.deviceId == deviceId) return SyncRunResult.SUCCESS
            val existingTask = repository.taskForEvent(spaceId, event.revision)
            if (existingTask?.state == SyncTaskState.COMPLETED) return SyncRunResult.SUCCESS
            if (existingTask?.state == SyncTaskState.FAILED) return SyncRunResult.FAILURE
            val oldPath = payload.oldPath ?: return SyncRunResult.FAILURE
            val newPath = payload.newPath ?: return SyncRunResult.FAILURE
            val oldPlan = RemoteDownloadPlanner.plan(oldPath, mappings) ?: return SyncRunResult.SUCCESS
            val newPlan = RemoteDownloadPlanner.plan(newPath, mappings) ?: return SyncRunResult.SUCCESS
            if (oldPlan.mappingId != newPlan.mappingId) {
                val marker = repository.getOrCreateEventMarkerTask(
                    spaceId, oldPlan.mappingId, oldPlan.localRelativePath, oldPlan.remoteRelativePath,
                    payload.fileId, event.revision
                )
                repository.markTaskFailed(marker, "SYNC_CONTRACT_INVALID", "跨映射重命名暂不支持")
                return SyncRunResult.FAILURE
            }
            val mapping = repository.mapping(oldPlan.mappingId)
            if (mapping == null || mapping.spaceId != spaceId || mapping.status != MappingStatus.ACTIVE) {
                return SyncRunResult.SUCCESS
            }
            val oldIndex = repository.file(mapping.id, oldPlan.localRelativePath)
            if (oldIndex == null || oldIndex.remoteFileId != payload.fileId) {
                return SyncRunResult.SUCCESS
            }
            if (isLocalDiverged(mapping, oldIndex)) {
                val marker = repository.getOrCreateEventMarkerTask(
                    spaceId, mapping.id, oldPlan.localRelativePath, oldPlan.remoteRelativePath,
                    payload.fileId, event.revision
                )
                repository.markTaskFailed(marker, "LOCAL_PATH_CONFLICT", "本地文件已修改，未执行远端重命名")
                return SyncRunResult.FAILURE
            }
            val newFileName = payload.newFileName ?: newPlan.localRelativePath.substringAfterLast('/')
            val moved = fileStore.move(mapping, oldPlan.localRelativePath, newPlan.localRelativePath)
            repository.moveIndex(
                mappingId = mapping.id,
                oldRelativePath = oldPlan.localRelativePath,
                newRelativePath = newPlan.localRelativePath,
                newFileName = newFileName,
                fileId = payload.fileId,
                version = payload.version ?: (oldIndex.remoteVersion ?: 0) + 1,
                docUri = moved.uri.toString(),
                size = moved.length().coerceAtLeast(0L),
                sha256 = oldIndex.sha256
            )
            repository.getOrCreateEventMarkerTask(
                spaceId, mapping.id, newPlan.localRelativePath, newPlan.remoteRelativePath,
                payload.fileId, event.revision
            ).let { repository.completeTask(it) }
            return SyncRunResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalPathConflictException) {
            return failEventTask(spaceId, event.revision, "LOCAL_PATH_CONFLICT", e.message ?: "本地路径冲突")
        } catch (e: FolderAccessException) {
            return failEventTask(spaceId, event.revision, "ACCESS_REVOKED", e.message ?: "目录授权失效")
        } catch (e: Exception) {
            return failEventTask(spaceId, event.revision, "SYNC_ERROR", e.message ?: "应用远端重命名失败")
        }
    }

    /** 本地文件相对上次同步索引的 sha 是否发生偏离（文件缺失或无法读取不算偏离）。 */
    private suspend fun isLocalDiverged(
        mapping: com.photosync.app.data.local.LocalFolderMapping,
        oldIndex: LocalFileIndex?
    ): Boolean {
        if (oldIndex == null) return false
        val knownSha = oldIndex.sha256
        if (knownSha.isNullOrBlank()) return false
        val target = try {
            fileStore.findTarget(mapping, oldIndex.relativePath)
        } catch (e: Exception) {
            return false
        } ?: return false
        if (!target.isFile || !target.canRead()) return false
        val digest = try {
            fileStore.digest(target)
        } catch (e: Exception) {
            return false
        }
        return !digest.sha256.equals(knownSha, ignoreCase = true)
    }

    /**
     * 本地版本与远端冲突时，把本地文件复制为冲突副本并登记新文件上传任务。
     * 冲突副本以新路径（`name (conflict from <device>).ext`）提交，双方内容都不会丢失。
     */
    private suspend fun preserveLocalAsConflictCopy(
        mapping: com.photosync.app.data.local.LocalFolderMapping,
        oldIndex: LocalFileIndex
    ) {
        val copyPath = fileStore.copyToConflictCopy(mapping, oldIndex.relativePath, mapping.deviceId)
        val copyFile = fileStore.findTarget(mapping, copyPath)
            ?: throw SafFileException("无法读取冲突副本：$copyPath")
        val digest = fileStore.digest(copyFile)
        repository.enqueueNewFileUpload(
            spaceId = mapping.spaceId,
            mapping = mapping,
            localPath = copyPath,
            size = digest.size,
            sha256 = digest.sha256
        )
    }

    private suspend fun failEventTask(
        spaceId: String,
        revision: String,
        code: String,
        message: String
    ): SyncRunResult {
        val task = repository.taskForEvent(spaceId, revision) ?: return SyncRunResult.FAILURE
        repository.markTaskFailed(task, code, message)
        return SyncRunResult.FAILURE
    }

    /**
     * 规划阶段已预建下载任务、但执行阶段判定无需下载（如本机事件回声）时，
     * 把遗留的等待任务标记为完成，避免任务中心出现永远待处理的幽灵任务。
     */
    private suspend fun settleSkippedEventTask(spaceId: String, revision: String) {
        val task = repository.taskForEvent(spaceId, revision) ?: return
        if (task.state == SyncTaskState.PENDING || task.state == SyncTaskState.RETRY) {
            repository.completeTask(task)
        }
    }

    /**
     * 任务行是否可提供「本地 / 云端」保留选择：
     * 仅限下载类任务因目标路径同名冲突（LOCAL_PATH_CONFLICT）失败的场景。
     */
    fun isResolvablePathConflict(task: LocalSyncTask): Boolean =
        task.type == SyncTaskType.DOWNLOAD &&
            task.state == SyncTaskState.FAILED &&
            task.error?.startsWith("LOCAL_PATH_CONFLICT") == true &&
            !task.fileId.isNullOrBlank() &&
            !task.objectId.isNullOrBlank()

    /** 本地冲突文件大小（用于决策展示）；文件缺失或不可读时返回 null。 */
    suspend fun localConflictFileSize(task: LocalSyncTask): Long? {
        val mapping = repository.mapping(task.mappingId) ?: return null
        return try {
            fileStore.findTarget(mapping, task.localRelativePath)
                ?.takeIf { it.isFile }?.length()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 冲突「保留云端」：本地冲突文件移入应用回收站并清索引，任务重新排队以下载云端版本。 */
    suspend fun resolveConflictKeepCloud(task: LocalSyncTask): String? {
        return try {
            val mapping = repository.mapping(task.mappingId) ?: return "映射不存在"
            if (!fileStore.moveToTrash(mapping, task.localRelativePath)) {
                return "本地文件已不存在或无法移入回收站"
            }
            repository.deleteIndex(task.mappingId, task.localRelativePath)
            repository.retryTask(task.id)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "处理失败"
        }
    }

    /** 冲突「保留本地」：查询云端当前版本后，把本地内容按 file_updated 覆盖提交，严格对齐两端。 */
    suspend fun resolveConflictKeepLocal(task: LocalSyncTask): String? {
        return try {
            val deviceId = repository.currentDeviceId() ?: return "设备未注册"
            val mapping = repository.mapping(task.mappingId) ?: return "映射不存在"
            val fileId = task.fileId ?: return "缺少云端文件标识"
            val target = fileStore.findTarget(mapping, task.localRelativePath)
                ?: return "本地文件已不存在"
            if (!target.isFile) return "本地路径不是文件"
            val digest = fileStore.digest(target)
            val versions = when (val result = apiCall { api.getFileVersions(fileId, deviceId) }) {
                is ApiResult.Failure -> return "查询云端版本失败：${result.message}"
                is ApiResult.Success -> result.data
            }
            val current = versions.versions.firstOrNull() ?: return "云端文件已不存在，无法覆盖"
            if (current.version <= 0) return "云端版本信息无效"
            val now = System.currentTimeMillis()
            val existing = repository.file(task.mappingId, task.localRelativePath)
            val indexRow = existing ?: LocalFileIndex(
                id = 0,
                mappingId = task.mappingId,
                relativePath = task.localRelativePath,
                fileName = task.localRelativePath.substringAfterLast('/'),
                size = digest.size,
                modifiedAt = now,
                sha256 = digest.sha256,
                docUri = target.uri.toString(),
                status = FileSyncStatus.PENDING_UPLOAD,
                lastScannedAt = now,
                remoteFileId = fileId,
                remoteVersion = current.version
            )
            repository.transaction {
                // 冲突任务就地完成，其职责由覆盖上传任务接管
                repository.completeTask(task)
                repository.upsertIndex(
                    indexRow.copy(
                        status = FileSyncStatus.PENDING_UPLOAD,
                        size = digest.size,
                        sha256 = digest.sha256,
                        remoteFileId = fileId,
                        remoteVersion = current.version
                    )
                )
                repository.enqueueOverwriteUpload(
                    spaceId = task.spaceId,
                    mapping = mapping,
                    localPath = task.localRelativePath,
                    size = digest.size,
                    sha256 = digest.sha256,
                    baseVersion = current.version,
                    fileId = fileId,
                    now = now
                )
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "处理失败"
        }
    }

    /** 文件列表条目是否处于同步失败状态（失败详情弹层 + 本地/云端/删除处理入口）。 */
    fun isResolvableFileEntry(file: LocalFileIndex): Boolean =
        file.status == FileSyncStatus.SYNC_ERROR

    /**
     * 文件列表失败处理「保留本地」：
     * 云端有对应文件时按 file_updated 覆盖云端当前版本；
     * 云端无对应文件（从未上传成功）时按新文件上传本地内容。
     */
    suspend fun resolveFileKeepLocal(file: LocalFileIndex): String? {
        return try {
            val mapping = repository.mapping(file.mappingId) ?: return "映射不存在"
            val target = fileStore.findTarget(mapping, file.relativePath)
                ?: return "本地文件已不存在，可改用「删除」"
            if (!target.isFile) return "本地路径不是文件"
            val digest = fileStore.digest(target)
            val deviceId = repository.currentDeviceId() ?: return "设备未注册"
            val fileId = file.remoteFileId
            if (fileId == null) {
                // 从未上传成功：先按路径查询云端占用者。路径已被其他文件占用时改为覆盖上传，
                // 否则新文件上传会再次被服务端 FILE_ALREADY_EXISTS 拒绝，形成死循环。
                val occupant = when (
                    val result = apiCall { api.getFileByPath(mapping.spaceId, deviceId, file.relativePath) }
                ) {
                    is ApiResult.Success -> result.data.file
                    is ApiResult.Failure -> return "查询云端文件失败：${result.message}"
                }
                val occupantFileId = occupant?.fileId
                if (occupantFileId != null) {
                    val now = System.currentTimeMillis()
                    repository.transaction {
                        repository.upsertIndex(
                            file.copy(
                                status = FileSyncStatus.PENDING_UPLOAD,
                                size = digest.size,
                                sha256 = digest.sha256,
                                docUri = file.docUri.ifBlank { target.uri.toString() },
                                remoteFileId = occupantFileId,
                                remoteVersion = occupant.version
                            )
                        )
                        repository.enqueueOverwriteUpload(
                            spaceId = mapping.spaceId,
                            mapping = mapping,
                            localPath = file.relativePath,
                            size = digest.size,
                            sha256 = digest.sha256,
                            baseVersion = occupant.version,
                            fileId = occupantFileId,
                            now = now
                        )
                    }
                    return null
                }
                repository.transaction {
                    repository.upsertIndex(
                        file.copy(
                            status = FileSyncStatus.PENDING_UPLOAD,
                            size = digest.size,
                            sha256 = digest.sha256,
                            docUri = file.docUri.ifBlank { target.uri.toString() }
                        )
                    )
                    repository.enqueueFileUploadNew(
                        spaceId = mapping.spaceId,
                        mapping = mapping,
                        localPath = file.relativePath,
                        size = digest.size,
                        sha256 = digest.sha256
                    )
                }
                return null
            }
            val versions = when (val result = apiCall { api.getFileVersions(fileId, deviceId) }) {
                is ApiResult.Failure -> return "查询云端版本失败：${result.message}"
                is ApiResult.Success -> result.data
            }
            val current = versions.versions.firstOrNull() ?: return "云端文件已不存在，无法覆盖"
            if (current.version <= 0) return "云端版本信息无效"
            repository.transaction {
                repository.removeActiveTasksForPath(mapping.spaceId, mapping.id, file.relativePath)
                repository.upsertIndex(
                    file.copy(
                        status = FileSyncStatus.PENDING_UPLOAD,
                        size = digest.size,
                        sha256 = digest.sha256,
                        docUri = file.docUri.ifBlank { target.uri.toString() },
                        remoteFileId = fileId,
                        remoteVersion = current.version
                    )
                )
                repository.enqueueOverwriteUpload(
                    spaceId = mapping.spaceId,
                    mapping = mapping,
                    localPath = file.relativePath,
                    size = digest.size,
                    sha256 = digest.sha256,
                    baseVersion = current.version,
                    fileId = fileId,
                    now = System.currentTimeMillis()
                )
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "处理失败"
        }
    }

    /**
     * 文件列表失败处理「保留云端」：本地文件移入应用回收站、清索引，
     * 并按云端最新对象补建下载任务（无需依赖事件游标）。
     */
    suspend fun resolveFileKeepCloud(file: LocalFileIndex): String? {
        return try {
            val deviceId = repository.currentDeviceId() ?: return "设备未注册"
            val mapping = repository.mapping(file.mappingId) ?: return "映射不存在"
            var fileId = file.remoteFileId
            if (fileId == null) {
                // 上传失败的任务没有云端 ID：按路径查询当前占用该路径的云端文件
                fileId = when (
                    val result = apiCall { api.getFileByPath(mapping.spaceId, deviceId, file.relativePath) }
                ) {
                    is ApiResult.Success -> result.data.file?.fileId
                    is ApiResult.Failure -> return "查询云端文件失败：${result.message}"
                } ?: return "云端暂无此路径的文件，可改用「本地」或「删除」"
            }
            val versions = when (val result = apiCall { api.getFileVersions(fileId, deviceId) }) {
                is ApiResult.Failure -> return "查询云端版本失败：${result.message}"
                is ApiResult.Success -> result.data
            }
            val current = versions.versions.firstOrNull() ?: return "云端文件已不存在，可改用「删除」"
            val currentSha = current.sha256
            if (current.objectId.isBlank() || current.version <= 0 || currentSha.isNullOrBlank()) {
                return "云端版本信息无效"
            }
            applyKeepCloud(file, mapping, fileId, current.objectId, current.size.toLongOrNull() ?: 0L, currentSha)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "处理失败"
        }
    }

    /**
     * 批量失败处理「保留云端」：每个映射一次 scanManifest 拉取远端清单（含 objectId），
     * 其余全部本地处理（移回收站、清索引、补建下载任务），不再逐条请求后端。
     * 返回与入参对齐的错误信息，null 表示成功；清单未命中或拉取失败时回退单文件流程。
     */
    suspend fun resolveKeepCloudAll(files: List<LocalFileIndex>): List<String?> {
        val errors = arrayOfNulls<String>(files.size)
        val deviceId = repository.currentDeviceId()
        if (deviceId == null) {
            files.indices.forEach { errors[it] = "设备未注册" }
            return errors.toList()
        }
        files.withIndex().groupBy { it.value.mappingId }.forEach { (mappingId, group) ->
            val mapping = repository.mapping(mappingId)
            if (mapping == null) {
                group.forEach { (index, _) -> errors[index] = "映射不存在" }
                return@forEach
            }
            val manifest = when (
                val result = apiCall {
                    api.scanManifest(
                        mapping.spaceId,
                        ScanManifestRequest(
                            deviceId = deviceId,
                            mappingId = mapping.id,
                            scanComplete = true,
                            files = emptyList()
                        )
                    )
                }
            ) {
                is ApiResult.Failure -> {
                    // 清单拉取失败：回退单文件流程，保留原始错误语义
                    group.forEach { (index, file) -> errors[index] = resolveFileKeepCloud(file) }
                    return@forEach
                }
                is ApiResult.Success -> result.data.remoteFiles
            }
            val byPath = manifest.associateBy { it.relativePath }
            val byFileId = manifest.associateBy { it.fileId }
            group.forEach { (index, file) ->
                // 与单文件流程同语义：有云端 ID 按文件查，无则按路径查占用者
                val remote = if (file.remoteFileId != null) {
                    byFileId[file.remoteFileId]
                } else {
                    byPath[file.relativePath]
                }
                errors[index] = if (remote == null) {
                    // 清单未命中（超过 10000 条截断、onlyMedia 过滤等）：回退单文件流程
                    resolveFileKeepCloud(file)
                } else {
                    val remoteSha = remote.sha256
                    val remoteObjectId = remote.objectId
                    if (remoteObjectId.isNullOrBlank() || remote.version <= 0 || remoteSha.isNullOrBlank()) {
                        "云端版本信息无效"
                    } else {
                        applyKeepCloud(file, mapping, remote.fileId, remoteObjectId, remote.size, remoteSha)
                    }
                }
            }
        }
        return errors.toList()
    }

    /**
     * 「保留云端」共享的本地收尾：本地文件移入应用回收站、清索引、补建下载任务。
     * 本地文件可能已不存在：缺失时直接重建下载即可。
     */
    private suspend fun applyKeepCloud(
        file: LocalFileIndex,
        mapping: LocalFolderMapping,
        fileId: String,
        objectId: String,
        size: Long,
        sha256: String
    ): String? {
        fileStore.findTarget(mapping, file.relativePath)?.let {
            if (!fileStore.moveToTrash(mapping, file.relativePath)) {
                return "本地文件无法移入回收站"
            }
        }
        repository.transaction {
            repository.removeActiveTasksForPath(mapping.spaceId, mapping.id, file.relativePath)
            repository.deleteIndex(file.mappingId, file.relativePath)
            repository.enqueueFileDownloadTask(
                spaceId = mapping.spaceId,
                mappingId = mapping.id,
                localPath = file.relativePath,
                fileId = fileId,
                objectId = objectId,
                size = size,
                sha256 = sha256
            )
        }
        return null
    }

    /**
     * 文件列表失败处理「删除」：本地文件移入应用回收站、清索引，
     * 并提交云端删除（file_deleted 事件会让其他设备同步移除本地副本）。
     */
    suspend fun resolveFileDeleteBoth(file: LocalFileIndex): String? {
        return try {
            val mapping = repository.mapping(file.mappingId) ?: return "映射不存在"
            // 本地文件可能已不存在：缺失时跳过移入回收站
            fileStore.findTarget(mapping, file.relativePath)?.let {
                if (!fileStore.moveToTrash(mapping, file.relativePath)) {
                    return "本地文件无法移入回收站"
                }
            }
            val deviceId = repository.currentDeviceId()
            var fileId = file.remoteFileId
            if (fileId == null && deviceId != null) {
                // 任务代表行（从未上传成功）没有云端 ID：按路径查询占用者，确保两端都删
                fileId = when (
                    val result = apiCall { api.getFileByPath(mapping.spaceId, deviceId, file.relativePath) }
                ) {
                    is ApiResult.Success -> result.data.file?.fileId
                    is ApiResult.Failure -> null
                }
            }
            val current = if (fileId != null && deviceId != null) {
                when (val result = apiCall { api.getFileVersions(fileId, deviceId) }) {
                    is ApiResult.Failure -> null
                    is ApiResult.Success -> result.data.versions.firstOrNull()
                }
            } else {
                null
            }
            repository.transaction {
                repository.removeActiveTasksForPath(mapping.spaceId, mapping.id, file.relativePath)
                repository.deleteIndex(file.mappingId, file.relativePath)
                // 云端无对应文件时仅清理本地
                if (current != null && current.version > 0) {
                    repository.enqueueFileDeleteTask(
                        spaceId = mapping.spaceId,
                        mapping = mapping,
                        localPath = file.relativePath,
                        fileId = fileId!!,
                        baseVersion = current.version
                    )
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "处理失败"
        }
    }

    /**
     * 任务列表失败详情与处理共用的索引行：优先真实索引；
     * 从未落索引（如下载中途失败）时以任务元数据构造代表行，docUri 留空由处理流程回填。
     */
    suspend fun taskFailureIndex(task: LocalSyncTask): LocalFileIndex {
        return repository.file(task.mappingId, task.localRelativePath)
            ?: LocalFileIndex(
                id = 0,
                mappingId = task.mappingId,
                relativePath = task.localRelativePath,
                fileName = task.localRelativePath.substringAfterLast('/'),
                size = task.size,
                modifiedAt = 0L,
                sha256 = null,
                docUri = "",
                status = FileSyncStatus.SYNC_ERROR,
                lastScannedAt = 0L,
                remoteFileId = task.fileId,
                remoteVersion = null
            )
    }

    /** 失败详情弹层的数据：云端最新版本信息 + 本地文件实际状态。 */
    suspend fun fileFailureInfo(file: LocalFileIndex): FileFailureInfo {
        val mapping = repository.mapping(file.mappingId)
        val target = mapping?.let {
            runCatching { fileStore.findTarget(it, file.relativePath) }.getOrNull()
        }?.takeIf { it.isFile }
        val localSize = target?.length()
        val localModifiedAt = target?.lastModified()
        val deviceId = repository.currentDeviceId()
        var cloud: CloudVersionInfo? = null
        var cloudError: String? = null
        if (deviceId != null) {
            val fileId = file.remoteFileId
            if (fileId != null) {
                when (val result = apiCall { api.getFileVersions(fileId, deviceId) }) {
                    is ApiResult.Success -> result.data.versions.firstOrNull()?.let {
                        cloud = CloudVersionInfo(
                            version = it.version,
                            size = it.size.toLongOrNull() ?: 0L,
                            createdAtIso = it.createdAt
                        )
                    }
                    is ApiResult.Failure -> cloudError = result.message
                }
            } else if (mapping != null) {
                // 上传失败的任务从未产生云端 ID：按路径查询当前占用该路径的云端文件
                //（通常是其他设备先上传的同名文件），避免误导为“云端暂无文件”。
                when (val result = apiCall { api.getFileByPath(mapping.spaceId, deviceId, file.relativePath) }) {
                    is ApiResult.Success -> result.data.file?.let {
                        cloud = CloudVersionInfo(
                            version = it.version,
                            size = it.size.toLongOrNull() ?: 0L,
                            createdAtIso = it.createdAt
                        )
                    }
                    is ApiResult.Failure -> cloudError = result.message
                }
            }
        }
        return FileFailureInfo(
            cloud = cloud,
            cloudError = cloudError,
            localSize = localSize,
            localModifiedAt = localModifiedAt
        )
    }

    private suspend fun failDownload(
        task: LocalSyncTask,
        code: String,
        message: String,
        conflict: LocalFileIndex
    ): SyncRunResult {
        repository.markTaskFailed(task, code, message, conflict)
        return SyncRunResult.FAILURE
    }

    private fun localIndex(
        mappingId: String,
        path: String,
        document: DocumentFile,
        size: Long,
        sha256: String?,
        payload: SyncEventPayloadDto,
        old: LocalFileIndex?
    ) = LocalFileIndex(
        id = old?.id ?: 0,
        mappingId = mappingId,
        relativePath = path,
        fileName = document.name ?: path.substringAfterLast('/'),
        size = size,
        modifiedAt = document.lastModified(),
        sha256 = sha256,
        docUri = document.uri.toString(),
        status = FileSyncStatus.SYNCED,
        lastScannedAt = old?.lastScannedAt ?: System.currentTimeMillis(),
        remoteFileId = payload.fileId,
        remoteVersion = payload.version
    )

    private suspend fun uploadRequestFailure(
        task: LocalSyncTask,
        failure: ApiResult.Failure
    ): SyncRunResult {
        if (failure.isNetwork) {
            repository.markTaskRetry(task, "NETWORK_ERROR", failure.message)
            return SyncRunResult.RETRY
        }
        return failUpload(task, failure.code ?: "UPLOAD_REQUEST_FAILED", failure.message)
    }
    private suspend fun downloadRequestFailure(
        task: LocalSyncTask,
        failure: ApiResult.Failure
    ): SyncRunResult {
        if (failure.isNetwork) {
            repository.markTaskRetry(task, "NETWORK_ERROR", failure.message)
            return SyncRunResult.RETRY
        }
        repository.markTaskFailed(task, failure.code ?: "DOWNLOAD_REQUEST_FAILED", failure.message)
        // 服务端对象已永久丢失（404 OBJECT_NOT_FOUND）：记录失败任务后跳过该事件，
        // 否则游标会被永久卡住，后续所有变更都无法同步。
        if (failure.code == OBJECT_NOT_FOUND) {
            return SyncRunResult.SUCCESS
        }
        return SyncRunResult.FAILURE
    }

    /**
     * 发起一次下载请求，供 SafFileStore.downloadVerified 的 fetch 回调使用。
     * rangeStart > 0 时携带 Range 头请求剩余字节，服务端返回 206 + Content-Range 供续传校验。
     */
    private suspend fun downloadChunk(objectId: String, deviceId: String, rangeStart: Long): DownloadChunk {
        val response = try {
            api.downloadObject(objectId, deviceId, range = if (rangeStart > 0) "bytes=$rangeStart-" else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw DownloadRequestFailureException(
                ApiResult.Failure("NETWORK_ERROR", "无法连接服务器，请检查网络与服务器地址", isNetwork = true)
            )
        } catch (e: Exception) {
            throw DownloadRequestFailureException(ApiResult.Failure("UNKNOWN_ERROR", e.message ?: "发生未知错误"))
        }
        if (response.code() == 416) {
            // 服务器无法满足续传区间（临时文件与云端版本不匹配）：交回 SafFileStore 清空从头下载
            response.raw().close()
            return DownloadChunk(ByteArrayInputStream(ByteArray(0)), 416, null)
        }
        if (!response.isSuccessful) {
            val detail = parseErrorMessage(response.errorBody())
            response.raw().close()
            throw DownloadRequestFailureException(
                ApiResult.Failure(detail?.code, detail?.message ?: "请求失败（HTTP ${response.code()}）")
            )
        }
        val body = response.body()
        if (body == null) {
            response.raw().close()
            throw DownloadRequestFailureException(ApiResult.Failure("DOWNLOAD_EMPTY_BODY", "下载响应缺少内容"))
        }
        return DownloadChunk(
            stream = body.byteStream(),
            statusCode = response.code(),
            rangeStart = parseContentRangeStart(response.headers()["Content-Range"])
        )
    }

    /** 解析 Content-Range 头（如 "bytes 1048576-2097151/2097152"）中的起始字节。 */
    private fun parseContentRangeStart(header: String?): Long? =
        header?.let { Regex("^bytes (\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    private fun isObjectMissing(error: String?): Boolean =
        error?.startsWith(OBJECT_NOT_FOUND) == true

    private suspend fun failUpload(
        task: LocalSyncTask,
        code: String,
        message: String
    ): SyncRunResult {
        repository.markTaskFailed(task, code, message)
        return SyncRunResult.FAILURE
    }

    private fun validateUpload(
        upload: UploadDto,
        task: LocalSyncTask,
        expectedSize: Long,
        expectedSha256: String
    ): UploadSessionPlan {
        if (upload.uploadId.isBlank() || upload.spaceId != task.spaceId ||
            upload.relativePath != task.remoteRelativePath || upload.objectId.isBlank()
        ) {
            throw SyncPlanningException("服务端上传会话与本地任务不一致")
        }
        val size = DecimalContract.long(upload.size, "upload.size")
        val sha256 = DecimalContract.requireSha256(upload.sha256)
        val partSize = DecimalContract.long(upload.partSize, "upload.partSize", false)
        if (size != expectedSize || sha256 != expectedSha256) {
            throw SyncPlanningException("服务端上传会话的内容校验信息不一致")
        }
        val partCountLong = if (size == 0L) 0L else ((size - 1L) / partSize) + 1L
        if (partCountLong > Int.MAX_VALUE) throw SyncPlanningException("上传分片数量过大")
        val partCount = partCountLong.toInt()
        val seen = mutableSetOf<Int>()
        var bytes = 0L
        for (part in upload.uploadedParts) {
            if (part.partNumber < 1 || part.partNumber > partCount || !seen.add(part.partNumber)) {
                throw SyncPlanningException("服务端 uploadedParts 包含无效分片")
            }
            val expectedPartSize = expectedPartSize(size, partSize, part.partNumber)
            if (DecimalContract.long(part.size, "part.size", false) != expectedPartSize) {
                throw SyncPlanningException("服务端 uploadedParts 的分片长度不一致")
            }
            DecimalContract.requireSha256(part.sha256)
            bytes += expectedPartSize
        }
        val firstMissing = if (partCount == 0) 1 else {
            (1..partCount).firstOrNull { it !in seen } ?: (partCount + 1)
        }
        return UploadSessionPlan(size, partSize, partCount, upload.uploadedParts, bytes, firstMissing)
    }

    private fun expectedPartSize(size: Long, partSize: Long, partNumber: Int): Long {
        val offset = (partNumber - 1).toLong() * partSize
        return minOf(partSize, size - offset)
    }

    private data class UploadSessionPlan(
        val size: Long,
        val partSize: Long,
        val partCount: Int,
        val parts: List<com.photosync.app.data.remote.UploadedPartDto>,
        val bytesTransferred: Long,
        val firstMissingPart: Int
    )

    private companion object {
        const val PART_SIZE = 8L * 1024L * 1024L
        const val PAGE_SIZE = 100
        const val MAX_MANIFEST_FILES = 10000
        const val OBJECT_NOT_FOUND = "OBJECT_NOT_FOUND"

        /** 同步权心跳间隔：小于服务端 SYNC_LOCK_TTL_MS（默认 90s），保证持续续期 */

        /** 需要下载对象的远端事件类型（file_restored 与新建/修改一样需要下载）。 */
        val OBJECT_EVENT_TYPES = setOf("file_created", "file_updated", "file_restored")
    }
}

/** 云端最新版本摘要（失败详情弹层展示）。 */
data class CloudVersionInfo(
    val version: Int,
    val size: Long,
    val createdAtIso: String?
)

/** 文件失败详情：云端信息（null = 云端无对应文件）+ 本地文件实际状态。 */
data class FileFailureInfo(
    val cloud: CloudVersionInfo?,
    val cloudError: String?,
    val localSize: Long?,
    val localModifiedAt: Long?
)

/** 当前任务被用户取消/移到队列末尾，只中断该任务，不影响整轮同步。 */
private class TaskAbortedException : RuntimeException()

/** 下载请求失败：在 fetch 回调内包装 ApiResult.Failure 传播到调用点，统一走 downloadRequestFailure 处理。 */
private class DownloadRequestFailureException(val failure: ApiResult.Failure) : RuntimeException(failure.message)
