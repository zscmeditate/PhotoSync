package com.photosync.app.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.photosync.app.data.sync.dedupeRemoteBindings
import kotlinx.coroutines.flow.Flow

/** 设备本地缓存（服务端设备表的镜像，标记本机） */
@Entity(tableName = "local_devices")
data class LocalDevice(
    @PrimaryKey val id: String,
    val deviceName: String,
    val platform: String,
    val credentialStatus: String,
    val isPrimary: Boolean = false,
    /** 被移除设备是否正在等待主设备同意重新加入 */
    val isRejoinRequested: Boolean = false,
    val isThisDevice: Boolean,
    val online: Boolean,
    val createdAt: String?,
    val lastSeenAt: String?
)

@Dao
interface LocalDeviceDao {

    @Query("SELECT * FROM local_devices ORDER BY isThisDevice DESC, deviceName ASC")
    fun observeAll(): Flow<List<LocalDevice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devices: List<LocalDevice>)

    @Query("DELETE FROM local_devices")
    suspend fun clear()
}

@Entity(tableName = "local_spaces")
data class LocalSpace(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val ownerId: String,
    val role: String,
    val revision: String,
    val memberCount: Int,
    val mappingCount: Int,
    val createdAt: String?,
    val updatedAt: String?,
    val lastSyncedAt: String?
)

@Dao
interface LocalSpaceDao {
    @Query("SELECT * FROM local_spaces ORDER BY updatedAt DESC, name ASC")
    fun observeAll(): Flow<List<LocalSpace>>

    @Query("SELECT * FROM local_spaces")
    suspend fun getAll(): List<LocalSpace>

    @Query("SELECT * FROM local_spaces WHERE id = :id")
    fun observeById(id: String): Flow<LocalSpace?>

    @Query("SELECT * FROM local_spaces WHERE id = :id")
    suspend fun getById(id: String): LocalSpace?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(spaces: List<LocalSpace>)

    @Query("UPDATE local_spaces SET mappingCount = MAX(0, mappingCount + :delta) WHERE id = :spaceId")
    suspend fun adjustMappingCount(spaceId: String, delta: Int)

    @Query("DELETE FROM local_spaces WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM local_spaces")
    suspend fun clear()
}

object MappingStatus {
    const val ACTIVE = "ACTIVE"
    const val ACCESS_REVOKED = "ACCESS_REVOKED"
    const val SCANNING = "SCANNING"
    const val ERROR = "ERROR"
}

@Entity(
    tableName = "local_folder_mappings",
    indices = [Index(value = ["spaceId", "deviceId"], unique = true)]
)
data class LocalFolderMapping(
    @PrimaryKey val id: String,
    val spaceId: String,
    val deviceId: String,
    val localPathLabel: String,
    val treeUri: String,
    val onlyMedia: Boolean,
    val status: String,
    val lastScanComplete: Boolean,
    val lastScannedAt: String?,
    val createdAt: String?
)

data class MappingFileCount(val mappingId: String, val fileCount: Int, val totalSize: Long)

@Dao
interface LocalFolderMappingDao {
    @Query("SELECT * FROM local_folder_mappings WHERE spaceId = :spaceId AND deviceId = :deviceId ORDER BY createdAt ASC, localPathLabel ASC")
    fun observeBySpaceAndDevice(spaceId: String, deviceId: String): Flow<List<LocalFolderMapping>>

    @Query("SELECT * FROM local_folder_mappings WHERE deviceId = :deviceId")
    fun observeByDevice(deviceId: String): Flow<List<LocalFolderMapping>>

    @Query("SELECT * FROM local_folder_mappings WHERE deviceId = :deviceId")
    suspend fun getByDevice(deviceId: String): List<LocalFolderMapping>

    @Query("SELECT * FROM local_folder_mappings WHERE id = :id")
    fun observeById(id: String): Flow<LocalFolderMapping?>

    @Query("SELECT * FROM local_folder_mappings WHERE id = :id")
    suspend fun getById(id: String): LocalFolderMapping?

    @Query("SELECT * FROM local_folder_mappings WHERE spaceId = :spaceId AND deviceId = :deviceId")
    suspend fun getBySpaceAndDevice(spaceId: String, deviceId: String): List<LocalFolderMapping>

    @Query("SELECT * FROM local_folder_mappings WHERE spaceId = :spaceId")
    suspend fun getBySpace(spaceId: String): List<LocalFolderMapping>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: LocalFolderMapping)

    /** 恢复上次被中断（进程被杀/同步被取消）后停留在“扫描中”的映射，避免永久不参与同步。 */
    @Query("UPDATE local_folder_mappings SET status = 'ACTIVE' WHERE spaceId = :spaceId AND status = 'SCANNING'")
    suspend fun recoverScanning(spaceId: String)

    @Query("DELETE FROM local_folder_mappings WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM local_folder_mappings WHERE spaceId = :spaceId")
    suspend fun deleteBySpaceId(spaceId: String)

    @Query("DELETE FROM local_folder_mappings")
    suspend fun clear()

    @Query(
        """
        SELECT m.id AS mappingId, COUNT(f.id) AS fileCount, COALESCE(SUM(f.size), 0) AS totalSize
        FROM local_folder_mappings m
        LEFT JOIN local_file_index f ON f.mappingId = m.id
        WHERE m.spaceId = :spaceId AND m.deviceId = :deviceId
        GROUP BY m.id
        """
    )
    fun observeFileCounts(spaceId: String, deviceId: String): Flow<List<MappingFileCount>>
}

object FileSyncStatus {
    const val PENDING_UPLOAD = "PENDING_UPLOAD"
    const val PENDING_DOWNLOAD = "PENDING_DOWNLOAD"
    const val SYNCED = "SYNCED"
    const val CONFLICT = "CONFLICT"
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val SYNC_ERROR = "SYNC_ERROR"
}

@Entity(
    tableName = "local_file_index",
    indices = [Index(value = ["mappingId", "relativePath"], unique = true)]
)
data class LocalFileIndex(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mappingId: String,
    val relativePath: String,
    val fileName: String,
    val size: Long,
    val modifiedAt: Long,
    val sha256: String?,
    val docUri: String,
    val status: String,
    val lastScannedAt: Long,
    val remoteFileId: String? = null,
    val remoteVersion: Int? = null
)

@Dao
interface LocalFileIndexDao {
    @Query("SELECT * FROM local_file_index WHERE mappingId = :mappingId ORDER BY relativePath ASC")
    fun observeByMapping(mappingId: String): Flow<List<LocalFileIndex>>

    @Query("SELECT * FROM local_file_index WHERE mappingId = :mappingId")
    suspend fun getByMapping(mappingId: String): List<LocalFileIndex>

    @Query("SELECT * FROM local_file_index WHERE mappingId = :mappingId AND relativePath = :relativePath LIMIT 1")
    suspend fun getByPath(mappingId: String, relativePath: String): LocalFileIndex?

    @Query("DELETE FROM local_file_index WHERE mappingId = :mappingId AND relativePath = :relativePath")
    suspend fun deleteByPath(mappingId: String, relativePath: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: LocalFileIndex)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<LocalFileIndex>)

    @Query("DELETE FROM local_file_index WHERE mappingId = :mappingId")
    suspend fun clearByMapping(mappingId: String)

    @Query("DELETE FROM local_file_index")
    suspend fun clear()

    /** 完整扫描成功后一次性替换索引，并保留 Phase 3 将使用的同步状态。 */
    @Transaction
    suspend fun replaceAfterCompleteScan(mappingId: String, scannedFiles: List<LocalFileIndex>) {
        val previous = getByMapping(mappingId).associateBy { it.relativePath }
        val merged = dedupeRemoteBindings(scannedFiles.map { file ->
            val old = previous[file.relativePath]
            file.copy(
                id = old?.id ?: 0,
                status = old?.status ?: FileSyncStatus.PENDING_UPLOAD,
                remoteFileId = old?.remoteFileId,
                remoteVersion = old?.remoteVersion
            )
        })
        clearByMapping(mappingId)
        upsertAll(merged)
    }
}

@Database(
    entities = [
        LocalDevice::class,
        LocalSpace::class,
        LocalFolderMapping::class,
        LocalFileIndex::class,
        LocalSyncTask::class,
        LocalSyncCursor::class
    ],
    // 基线版本：历史迁移已合并，后续结构变化从 1 开始新增迁移
    version = 1,
    exportSchema = false
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun localDeviceDao(): LocalDeviceDao
    abstract fun localSpaceDao(): LocalSpaceDao
    abstract fun localFolderMappingDao(): LocalFolderMappingDao
    abstract fun localFileIndexDao(): LocalFileIndexDao
    abstract fun localSyncTaskDao(): LocalSyncTaskDao
    abstract fun localSyncCursorDao(): LocalSyncCursorDao
}
