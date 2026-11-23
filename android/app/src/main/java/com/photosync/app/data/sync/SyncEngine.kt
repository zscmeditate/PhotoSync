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
import com.photosync.app.data.repository.SyncRepository
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 一次同步运行的结果；RETRY 由 WorkManager 按退避策略再次执行。 */
enum class SyncRunResult { SUCCESS, RETRY, FAILURE }

/** 仅实现 Phase 3 的新增上传与 file_created 增量下载。 */
class SyncEngine(
    private val repository: SyncRepository,
    private val fileStore: SafFileStore
) {
    private val api = repository.apiClient.api

    suspend fun syncAll(): SyncRunResult {
        var result = SyncRunResult.SUCCESS
        for (spaceId in repository.spaceIds()) {
            when (syncSpace(spaceId)) {
                SyncRunResult.SUCCESS -> Unit
                SyncRunResult.RETRY -> if (result == SyncRunResult.SUCCESS) {
                    result = SyncRunResult.RETRY
                }
                SyncRunResult.FAILURE -> result = SyncRunResult.FAILURE
            }
        }
        return result
    }

    suspend fun syncSpace(spaceId: String): SyncRunResult {
        val deviceId = repository.currentDeviceId() ?: return SyncRunResult.FAILURE
        repository.recoverRunning(spaceId)
        val mappings = repository.activeMappings(spaceId, deviceId)
        try {
            for (mapping in mappings) {
                val oldIndex = repository.files(mapping.id)
                when (val scan = repository.scanMapping(mapping.id)) {
                    is ApiResult.Failure -> return SyncRunResult.FAILURE
                    is ApiResult.Success -> Unit
                }
                val newIndex = repository.files(mapping.id)
                // 新绑定/重新绑定的映射先与远端清单核对，已存在的相同文件直接建立关联，
                // 并以关联后的索引作为差异基线，避免把相同文件重复规划为上传。
                var baseline = oldIndex
                var current = newIndex
                if (oldIndex.isEmpty() && newIndex.isNotEmpty()) {
                    when (reconcileFreshMapping(mapping, newIndex)) {
                        SyncRunResult.RETRY -> return SyncRunResult.RETRY
                        else -> Unit
                    }
                    current = repository.files(mapping.id)
                    baseline = current.filter {
                        it.status == FileSyncStatus.SYNCED && !it.remoteFileId.isNullOrBlank()
                    }
                }
                repository.planAndEnqueueLocalChanges(spaceId, mapping, baseline, current)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncRunResult.FAILURE
        }

        var taskFailed = false
        while (true) {
            val task = repository.nextPendingUpload(spaceId) ?: break
            when (upload(task)) {
                SyncRunResult.SUCCESS -> Unit
                SyncRunResult.RETRY -> return SyncRunResult.RETRY
                SyncRunResult.FAILURE -> taskFailed = true
            }
        }
        while (true) {
            val task = repository.nextPendingMutation(spaceId) ?: break
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
            taskFailed -> SyncRunResult.FAILURE
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
        try {
            DecimalContract.revision(since)
            val rules = mappings.map {
                SyncMappingRule(it.id, it.onlyMedia)
            }
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
                    if (changes.hasMore || DecimalContract.compareRevisions(changes.nextRevision, since) != 0) {
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

                var previous = since
                for (event in changes.events) {
                    val revision = DecimalContract.revision(event.revision)
                    if (DecimalContract.compareRevisions(revision, previous) <= 0) {
                        return SyncRunResult.FAILURE
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
                    previous = revision
                }
                if (DecimalContract.compareRevisions(changes.nextRevision, previous) != 0) {
                    return SyncRunResult.FAILURE
                }
                if (!changes.hasMore) return SyncRunResult.SUCCESS
                since = previous
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncRunResult.FAILURE
        }
    }

    private suspend fun processEvent(
        spaceId: String,
        deviceId: String,
        mappings: List<SyncMappingRule>,
        event: SyncEventDto
    ): SyncRunResult {
        val payload = event.payload ?: return SyncRunResult.FAILURE
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
                if (event.deviceId == deviceId) return SyncRunResult.SUCCESS
            } else if (SyncEventPlanner.shouldSkipOwnEvent(
                    event.deviceId, deviceId,
                    oldIndex?.remoteFileId, oldIndex?.sha256,
                    payload.fileId, expectedSha256
                )
            ) {
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

            val response = when (val result = apiCall { api.downloadObject(payload.objectId, deviceId) }) {
                is ApiResult.Failure -> return downloadRequestFailure(task, result)
                is ApiResult.Success -> result.data
            }
            try {
                response.byteStream().use { input ->
                    val downloaded = fileStore.downloadVerified(
                        mapping = mapping,
                        localRelativePath = plan.localRelativePath,
                        taskId = task.id,
                        input = input,
                        expectedSize = expectedSize,
                        expectedSha256 = expectedSha256
                    )
                    repository.markSynced(
                        task,
                        localIndex(mapping.id, plan.localRelativePath, downloaded, expectedSize, expectedSha256, payload, oldIndex)
                    )
                }
            } finally {
                response.close()
            }
            return SyncRunResult.SUCCESS
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
    }
}
