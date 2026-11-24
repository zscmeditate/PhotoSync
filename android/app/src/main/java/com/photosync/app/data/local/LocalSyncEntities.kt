package com.photosync.app.data.local

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
    val pendingMutations: Int = 0,
    val failedTasks: Int = 0,
    val runningTasks: Int = 0
)

@Dao
interface LocalSyncTaskDao {
    /** 任务统计：待处理仅统计从未执行的任务（等待执行/等待重试），进行中与失败单独计数。 */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN type = 'UPLOAD' AND state IN ('PENDING', 'RETRY') THEN 1 ELSE 0 END), 0) AS pendingUploads,
            COALESCE(SUM(CASE WHEN type = 'DOWNLOAD' AND state IN ('PENDING', 'RETRY') THEN 1 ELSE 0 END), 0) AS pendingDownloads,
            COALESCE(SUM(CASE WHEN type IN ('DELETE', 'RENAME') AND state IN ('PENDING', 'RETRY') THEN 1 ELSE 0 END), 0) AS pendingMutations,
            COALESCE(SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedTasks,
            COALESCE(SUM(CASE WHEN state = 'RUNNING' THEN 1 ELSE 0 END), 0) AS runningTasks
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: LocalSyncTask)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(task: LocalSyncTask): Long

    @Query("UPDATE local_sync_tasks SET state = 'PENDING', error = NULL, updatedAt = :now WHERE spaceId = :spaceId AND state = 'RUNNING'")
    suspend fun recoverRunning(spaceId: String, now: Long)

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

    /** 任务中心取消：仅允许取消尚未执行或已失败的任务，运行中的任务由 Worker 自行结束。 */
    @Query("DELETE FROM local_sync_tasks WHERE id = :id AND state IN ('PENDING', 'RETRY', 'FAILED')")
    suspend fun cancelTask(id: String)

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

    @Query("DELETE FROM local_sync_tasks")
    suspend fun clear()
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
