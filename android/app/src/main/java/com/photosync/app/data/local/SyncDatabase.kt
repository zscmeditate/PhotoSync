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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    /** 同一事务内整体替换设备缓存，避免先清空再写入导致列表瞬间变空闪烁。 */
    @Transaction
    suspend fun replaceAll(devices: List<LocalDevice>) {
        clear()
        upsertAll(devices)
    }
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
    val lastSyncedAt: String?,
    /** 本机为该空间选择的头像照片（应用私有目录内的文件路径），未设置为 null。 */
    val coverPath: String? = null,
    /** 头像对应的服务端版本（coverUpdatedAt），用于判断是否需要重新下载。 */
    val coverVersion: String? = null
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

    @Query("UPDATE local_spaces SET coverPath = :path, coverVersion = :version WHERE id = :spaceId")
    suspend fun updateCover(spaceId: String, path: String?, version: String?)

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

/** 某个空间在本机的文件总数（按其下所有本地目录映射的索引统计）。 */
data class SpaceFileCount(val spaceId: String, val fileCount: Int)

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

    @Query(
        """
        SELECT m.spaceId AS spaceId, COUNT(f.id) AS fileCount
        FROM local_folder_mappings m
        LEFT JOIN local_file_index f ON f.mappingId = m.id
        WHERE m.deviceId = :deviceId
        GROUP BY m.spaceId
        """
    )
    fun observeFileCountsBySpace(deviceId: String): Flow<List<SpaceFileCount>>
}

/**
 * 本地批量删除待确认记录：单次扫描删除数超过保护阈值时，先不向云端传播删除，
 * 而是把这批文件记录在这里（同时阻止兜底补齐逻辑把它们重新下载回来），等用户二次确认。
 */
@Entity(
    tableName = "local_pending_deletions",
    primaryKeys = ["mappingId", "relativePath"],
    indices = [Index("spaceId")]
)
data class LocalPendingDeletion(
    val mappingId: String,
    val spaceId: String,
    val relativePath: String,
    val fileId: String,
    val baseVersion: Int,
    val size: Long,
    val detectedAt: Long
)

@Dao
interface LocalPendingDeletionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LocalPendingDeletion>)

    @Query("SELECT * FROM local_pending_deletions WHERE spaceId = :spaceId ORDER BY relativePath ASC")
    suspend fun getBySpace(spaceId: String): List<LocalPendingDeletion>

    @Query("SELECT relativePath FROM local_pending_deletions WHERE spaceId = :spaceId")
    suspend fun pathsBySpace(spaceId: String): List<String>

    @Query("SELECT relativePath FROM local_pending_deletions WHERE mappingId = :mappingId")
    suspend fun pathsByMapping(mappingId: String): List<String>

    @Query("SELECT COUNT(*) FROM local_pending_deletions WHERE spaceId = :spaceId")
    fun observeCount(spaceId: String): Flow<Int>

    @Query("DELETE FROM local_pending_deletions WHERE spaceId = :spaceId")
    suspend fun deleteBySpace(spaceId: String)

    @Query("DELETE FROM local_pending_deletions WHERE mappingId = :mappingId")
    suspend fun deleteByMapping(mappingId: String)

    @Query("DELETE FROM local_pending_deletions WHERE mappingId = :mappingId AND relativePath IN (:paths)")
    suspend fun deleteByPaths(mappingId: String, paths: List<String>)
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
        LocalSyncCursor::class,
        LocalPendingDeletion::class
    ],
    // 基线版本：历史迁移已合并，后续结构变化从 1 开始新增迁移
    version = 4,
    exportSchema = false
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun localDeviceDao(): LocalDeviceDao
    abstract fun localSpaceDao(): LocalSpaceDao
    abstract fun localFolderMappingDao(): LocalFolderMappingDao
    abstract fun localFileIndexDao(): LocalFileIndexDao
    abstract fun localSyncTaskDao(): LocalSyncTaskDao
    abstract fun localSyncCursorDao(): LocalSyncCursorDao
    abstract fun localPendingDeletionDao(): LocalPendingDeletionDao
}

/** v1 -> v2：空间支持本机自定义头像，只新增可空的本地文件路径列。 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_spaces ADD COLUMN coverPath TEXT")
    }
}

/** v2 -> v3：头像改为服务端存储，新增服务端版本号用于差量刷新。 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_spaces ADD COLUMN coverVersion TEXT")
    }
}

/** v3 -> v4：批量删除需要用户二次确认，新增待确认记录表与任务确认标记。 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_pending_deletions` (
                `mappingId` TEXT NOT NULL,
                `spaceId` TEXT NOT NULL,
                `relativePath` TEXT NOT NULL,
                `fileId` TEXT NOT NULL,
                `baseVersion` INTEGER NOT NULL,
                `size` INTEGER NOT NULL,
                `detectedAt` INTEGER NOT NULL,
                PRIMARY KEY(`mappingId`, `relativePath`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_pending_deletions_spaceId` " +
                "ON `local_pending_deletions` (`spaceId`)"
        )
        db.execSQL("ALTER TABLE local_sync_tasks ADD COLUMN confirmed INTEGER NOT NULL DEFAULT 0")
    }
}
