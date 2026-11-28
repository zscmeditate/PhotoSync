package com.photosync.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

object SyncTaskType {
    const val UPLOAD = "UPLOAD"
    const val DOWNLOAD = "DOWNLOAD"
    const val DELETE = "DELETE"
    const val RENAME = "RENAME"
}

object SyncTaskState {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val RETRY = "RETRY"
    const val FAILED = "FAILED"
    const val COMPLETED = "COMPLETED"
}

@Entity(
    tableName = "local_sync_tasks",
    indices = [
        Index(value = ["spaceId", "state"]),
        Index(value = ["spaceId", "mappingId", "remoteRelativePath", "type"]),
        Index(value = ["spaceId", "eventRevision"], unique = true)
    ]
)
data class LocalSyncTask(
    @PrimaryKey val id: String,
    val spaceId: String,
    val mappingId: String,
    val type: String,
    val state: String,
    val localRelativePath: String,
    val remoteRelativePath: String,
    val fileId: String? = null,
    val objectId: String? = null,
    val uploadId: String? = null,
    val size: Long,
    val sha256: String? = null,
    val operationId: String,
    val eventRevision: String? = null,
    val uploadedParts: Int = 0,
    val bytesTransferred: Long = 0,
    val nextPartNumber: Int = 1,
    val attempt: Int = 0,
    val error: String? = null,
    val baseVersion: Int? = null,
    val oldLocalRelativePath: String? = null,
    /** 用户已在「待确认的批量删除」中确认，提交删除时携带 confirmBulkDelete 绕过服务端阈值。 */
    @ColumnInfo(defaultValue = "0")
    val confirmed: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "local_sync_cursors",
    primaryKeys = ["spaceId", "deviceId"]
)
data class LocalSyncCursor(
    val spaceId: String,
    val deviceId: String,
    val lastAppliedRevision: String,
    val serverRevision: String,
    val updatedAt: Long
)

data class SyncTaskStats(
    val pendingUploads: Int = 0,
    val pendingDownloads: Int = 0,
    val pendingUploadBytes: Long = 0,
    val pendingDownloadBytes: Long = 0,
    val pendingMutations: Int = 0,
    val pendingDeletes: Int = 0,
    val pendingRenames: Int = 0,
    val failedTasks: Int = 0,
    val runningTasks: Int = 0,
    val completedTasks: Int = 0
)

@Dao
interface LocalSyncTaskDao {
    /** 任务统计：各类型计数含执行中（RUNNING）任务，进行中/失败/完成单独计数。 */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN type = 'UPLOAD' AND state IN ('PENDING', 'RUNNING', 'RETRY') THEN 1 ELSE 0 END), 0) AS pendingUploads,
            COALESCE(SUM(CASE WHEN type = 'DOWNLOAD' AND state IN ('PENDING', 'RUNNING', 'RETRY') THEN 1 ELSE 0 END), 0) AS pendingDownloads,
            COALESCE(SUM(CASE WHEN type = 'UPLOAD' AND state IN ('PENDING', 'RUNNING', 'RETRY') THEN size ELSE 0 END), 0) AS pendingUploadBytes,
            COALESCE(SUM(CASE WHEN type = 'DOWNLOAD' AND state IN ('PENDING', 'RUNNING', 'RETRY') THEN size ELSE 0 END), 0) AS pendingDownloadBytes,
            COALESCE(SUM(CASE WHEN type IN ('DELETE', 'RENAME') AND state IN ('PENDING', 'RUNNING', 'RETRY') THEN 1 ELSE 0 END), 0) AS pendingMutations,
            COALESCE(SUM(CASE WHEN type = 'DELETE' AND state IN ('PENDING', 'RUNNING', 'RETRY') THEN 1 ELSE 0 END), 0) AS pendingDeletes,
            COALESCE(SUM(CASE WHEN type = 'RENAME' AND state IN ('PENDING', 'RUNNING', 'RETRY') THEN 1 ELSE 0 END), 0) AS pendingRenames,
            COALESCE(SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedTasks,
            COALESCE(SUM(CASE WHEN state = 'RUNNING' THEN 1 ELSE 0 END), 0) AS runningTasks,
            COALESCE(SUM(CASE WHEN state = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completedTasks
        FROM local_sync_tasks WHERE spaceId = :spaceId
        """
    )
    fun observeStats(spaceId: String): Flow<SyncTaskStats>

    /** 空间详情：该空间的进行中、待处理与失败任务，按空间隔离。 */
    @Query(
        """
        SELECT * FROM local_sync_tasks
        WHERE spaceId = :spaceId AND state IN ('PENDING', 'RUNNING', 'RETRY', 'FAILED')
        ORDER BY
            CASE state
                WHEN 'RUNNING' THEN 0
                WHEN 'RETRY' THEN 1
                WHEN 'PENDING' THEN 2
                ELSE 3
            END,
            updatedAt DESC
        """
    )
    fun observeActiveBySpace(spaceId: String): Flow<List<LocalSyncTask>>

    /** 活动记录：包含已完成任务在内的最近任务时间线。 */
    @Query("SELECT * FROM local_sync_tasks ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LocalSyncTask>>

    @Query(
        "SELECT * FROM local_sync_tasks WHERE spaceId = :spaceId ORDER BY updatedAt DESC LIMIT :limit"
    )
    fun observeRecentBySpace(spaceId: String, limit: Int): Flow<List<LocalSyncTask>>

    @Query("SELECT * FROM local_sync_tasks WHERE id = :id")
    suspend fun getById(id: String): LocalSyncTask?

    @Query("SELECT * FROM local_sync_tasks WHERE spaceId = :spaceId AND eventRevision = :eventRevision LIMIT 1")
    suspend fun getByEvent(spaceId: String, eventRevision: String): LocalSyncTask?

    @Query(
        """
        SELECT * FROM local_sync_tasks
        WHERE spaceId = :spaceId AND mappingId = :mappingId
          AND remoteRelativePath = :remoteRelativePath AND type = :type
        ORDER BY createdAt DESC LIMIT 1
        """
    )
    suspend fun getByRemotePath(
        spaceId: String,
        mappingId: String,
        remoteRelativePath: String,
        type: String
    ): LocalSyncTask?

    @Query(
        """
        SELECT * FROM local_sync_tasks
        WHERE spaceId = :spaceId AND type = 'UPLOAD' AND state IN ('PENDING', 'RETRY')
        ORDER BY createdAt ASC, id ASC LIMIT 1
        """
    )
    suspend fun nextPendingUpload(spaceId: String): LocalSyncTask?

    @Query(
        """
        SELECT * FROM local_sync_tasks
        WHERE spaceId = :spaceId AND type IN ('DELETE', 'RENAME') AND state IN ('PENDING', 'RETRY')
          AND (eventRevision IS NULL OR eventRevision = '')
        ORDER BY createdAt ASC, id ASC LIMIT 1
        """
    )
    suspend fun nextPendingMutation(spaceId: String): LocalSyncTask?

    @Query(
        """
        SELECT COUNT(*) FROM local_sync_tasks
        WHERE spaceId = :spaceId AND mappingId = :mappingId
          AND remoteRelativePath = :remoteRelativePath
          AND state IN ('PENDING', 'RUNNING', 'RETRY', 'FAILED')
        """
    )
    suspend fun countActiveTasks(spaceId: String, mappingId: String, remoteRelativePath: String): Int

    /**
     * 失败处理会为同一路径补建新的下载/上传任务；
     * 先移除该路径的旧活动任务，避免被去重逻辑判定为“已有任务”而静默跳过。
     */
    @Query(
        """
        DELETE FROM local_sync_tasks
        WHERE spaceId = :spaceId AND mappingId = :mappingId
          AND remoteRelativePath = :remoteRelativePath
          AND state IN ('PENDING', 'RETRY', 'FAILED')
        """
    )
    suspend fun deleteActiveTasksForRemotePath(
        spaceId: String,
        mappingId: String,
        remoteRelativePath: String
    )

    /**
     * 不依赖远端事件的独立下载任务（失败处理「云端优先」补建），按创建顺序取出执行。
     * ERROR 映射也参与：扫描失败只阻塞本地变更规划，不阻塞写入云端文件；
     * 目录正在扫描时先跳过，避免扫描期间写入干扰索引。
     */
    @Query(
        """
        SELECT t.* FROM local_sync_tasks t
        JOIN local_folder_mappings m ON m.id = t.mappingId
        WHERE t.spaceId = :spaceId AND t.type = 'DOWNLOAD' AND t.state IN ('PENDING', 'RETRY')
          AND (t.eventRevision IS NULL OR t.eventRevision = '')
          AND m.status IN ('ACTIVE', 'ERROR')
        ORDER BY t.createdAt ASC, t.id ASC LIMIT 1
        """
    )
    suspend fun nextStandaloneDownload(spaceId: String): LocalSyncTask?

    /**
     * 队列直跑（「刷新」）用：取出任意待执行下载任务（含事件驱动任务）。
     * 事件任务提前完成后，pullChanges 处理到对应事件时按“已完成”确认游标，不会重复下载。
     */
    @Query(
        """
        SELECT t.* FROM local_sync_tasks t
        JOIN local_folder_mappings m ON m.id = t.mappingId
        WHERE t.spaceId = :spaceId AND t.type = 'DOWNLOAD' AND t.state IN ('PENDING', 'RETRY')
          AND m.status IN ('ACTIVE', 'ERROR')
        ORDER BY t.createdAt ASC, t.id ASC LIMIT 1
        """
    )
    suspend fun nextQueuedDownload(spaceId: String): LocalSyncTask?

    /** 该空间下仍有进行中/失败任务（未处理完）的远端路径，补齐缺失文件时要跳过。 */
    @Query(
        """
        SELECT remoteRelativePath FROM local_sync_tasks
        WHERE spaceId = :spaceId AND state IN ('PENDING', 'RUNNING', 'RETRY', 'FAILED')
        UNION
        SELECT oldLocalRelativePath FROM local_sync_tasks
        WHERE spaceId = :spaceId AND state IN ('PENDING', 'RUNNING', 'RETRY', 'FAILED')
          AND oldLocalRelativePath IS NOT NULL
        """
    )
    suspend fun activeRemotePaths(spaceId: String): List<String>

    /** 清理源文件已不存在的失败上传任务（例如并发同步被改名抢走后遗留），避免长期显示失败。 */
    @Query(
        """
        DELETE FROM local_sync_tasks
        WHERE spaceId = :spaceId AND type = 'UPLOAD' AND state = 'FAILED'
          AND NOT EXISTS (
              SELECT 1 FROM local_file_index f
              WHERE f.mappingId = local_sync_tasks.mappingId
                AND f.relativePath = local_sync_tasks.localRelativePath
          )
        """
    )
    suspend fun deleteFailedUploadsWithoutLocalFile(spaceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: LocalSyncTask)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(task: LocalSyncTask): Long

    @Query("UPDATE local_sync_tasks SET state = 'PENDING', error = NULL, updatedAt = :now WHERE spaceId = :spaceId AND state = 'RUNNING'")
    suspend fun recoverRunning(spaceId: String, now: Long)

    /** 进程启动/界面回到前台且引擎空闲时，把上次被取消或进程被杀留下的 RUNNING 任务统一退回队列。 */
    @Query("UPDATE local_sync_tasks SET state = 'PENDING', error = NULL, updatedAt = :now WHERE state = 'RUNNING'")
    suspend fun recoverAllRunning(now: Long)

    // 远端对象已永久丢失的任务重试也必然失败，保留 FAILED 状态供用户查看/取消，避免反复入队。
    @Query(
        """
        UPDATE local_sync_tasks SET state = 'PENDING', updatedAt = :now
        WHERE spaceId = :spaceId AND state = 'FAILED'
          AND (error IS NULL OR error NOT LIKE 'OBJECT_NOT_FOUND%')
        """
    )
    suspend fun requeueFailed(spaceId: String, now: Long)

    @Query(
        """
        UPDATE local_sync_tasks
        SET uploadedParts = :uploadedParts, bytesTransferred = :bytesTransferred,
            nextPartNumber = :nextPartNumber, updatedAt = :updatedAt
        WHERE id = :taskId
        """
    )
    suspend fun updateUploadProgress(
        taskId: String,
        uploadedParts: Int,
        bytesTransferred: Long,
        nextPartNumber: Int,
        updatedAt: Long
    )

    /** 下载进度：仅更新已传输字节数（下载无分片概念）。 */
    @Query(
        """
        UPDATE local_sync_tasks SET bytesTransferred = :bytesTransferred, updatedAt = :updatedAt
        WHERE id = :taskId
        """
    )
    suspend fun updateDownloadProgress(taskId: String, bytesTransferred: Long, updatedAt: Long)

    /** 任务中心重试：失败/等待中的任务回到待执行队列。 */
    @Query(
        """
        UPDATE local_sync_tasks SET state = 'PENDING', error = NULL, updatedAt = :now
        WHERE id = :id AND state IN ('PENDING', 'RETRY', 'FAILED')
        """
    )
    suspend fun retryTask(id: String, now: Long)

    /** 任务中心取消：删除任务行；运行中的任务由引擎在分片/进度边界发现行已删除后中断传输。 */
    @Query("DELETE FROM local_sync_tasks WHERE id = :id AND state IN ('PENDING', 'RUNNING', 'RETRY', 'FAILED')")
    suspend fun cancelTask(id: String)

    /**
     * 取消进行中的上传/下载：不删除任务，而是回到待执行队列末尾。
     * createdAt 刷新为当前时间保证排到最后；进度归零，断点数据（uploadId/临时文件）仍可复用。
     */
    @Query(
        """
        UPDATE local_sync_tasks
        SET state = 'PENDING', error = NULL, createdAt = :now, updatedAt = :now,
            bytesTransferred = 0, uploadedParts = 0, nextPartNumber = 1
        WHERE id = :id AND state = 'RUNNING' AND type IN ('UPLOAD', 'DOWNLOAD')
        """
    )
    suspend fun moveRunningTaskToQueueEnd(id: String, now: Long)

    @Query("DELETE FROM local_sync_tasks WHERE mappingId = :mappingId")
    suspend fun deleteByMapping(mappingId: String)

    /** 本地文件已从扫描中消失且无远端关联（如被识别为系统回收站）时，撤销其未执行的上传任务。 */
    @Query(
        """
        DELETE FROM local_sync_tasks
        WHERE spaceId = :spaceId AND mappingId = :mappingId AND type = 'UPLOAD'
          AND localRelativePath = :localRelativePath AND state IN ('PENDING', 'RETRY', 'FAILED')
        """
    )
    suspend fun deleteUploadTasksForLocalPath(spaceId: String, mappingId: String, localRelativePath: String)

    @Query("DELETE FROM local_sync_tasks WHERE spaceId = :spaceId")
    suspend fun deleteBySpace(spaceId: String)

    /** 清空指定空间的已完成任务，返回删除行数。 */
    @Query("DELETE FROM local_sync_tasks WHERE spaceId = :spaceId AND state = 'COMPLETED'")
    suspend fun clearCompletedBySpace(spaceId: String): Int

    /** 清空指定空间的全部同步任务（含进行中、失败、已完成），返回删除行数。 */
    @Query("DELETE FROM local_sync_tasks WHERE spaceId = :spaceId")
    suspend fun clearTasksBySpace(spaceId: String): Int

    /** 清空全部同步任务，返回删除行数。 */
    @Query("DELETE FROM local_sync_tasks")
    suspend fun clearAllTasks(): Int
}

@Dao
interface LocalSyncCursorDao {
    @Query("SELECT * FROM local_sync_cursors WHERE spaceId = :spaceId AND deviceId = :deviceId")
    suspend fun get(spaceId: String, deviceId: String): LocalSyncCursor?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: LocalSyncCursor)

    @Query("DELETE FROM local_sync_cursors WHERE spaceId = :spaceId")
    suspend fun deleteBySpace(spaceId: String)

    @Query("DELETE FROM local_sync_cursors")
    suspend fun clear()
}
