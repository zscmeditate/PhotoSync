package com.photosync.app.di

import android.content.Context
import androidx.room.Room
import com.photosync.app.data.local.MIGRATION_1_2
import com.photosync.app.data.local.MIGRATION_2_3
import com.photosync.app.data.local.MIGRATION_3_4
import com.photosync.app.data.local.SpaceCoverStore
import com.photosync.app.data.local.SyncDatabase
import com.photosync.app.data.prefs.PendingBindingStore
import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.prefs.SettingsRepository
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.repository.AuthRepository
import com.photosync.app.data.repository.DeviceRepository
import com.photosync.app.data.repository.MaintenanceRepository
import com.photosync.app.data.repository.ServerRepository
import com.photosync.app.data.repository.SpaceRepository
import com.photosync.app.data.repository.SyncRepository
import com.photosync.app.data.sync.FolderScanner
import com.photosync.app.data.sync.SafFileStore
import com.photosync.app.data.sync.SyncEngine
import com.photosync.app.data.sync.SyncProgressNotifier
import com.photosync.app.data.sync.SyncRealtimeClient
import com.photosync.app.util.systemDeviceName

/** 简单的手动依赖容器，随 Application 创建。 */
class AppContainer(context: Context) {

    /** 系统里这台手机的设备名称，用于登录时自动登记本机 */
    val deviceName: String = systemDeviceName(context.applicationContext)

    val settingsRepository = SettingsRepository(context)

    val secureTokenStore = SecureTokenStore(context)

    val pendingBindingStore = PendingBindingStore(context)

    val spaceCoverStore = SpaceCoverStore(context)

    val database: SyncDatabase = Room.databaseBuilder(
        context.applicationContext,
        SyncDatabase::class.java,
        "photosync.db"
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        // 未命中迁移的结构变化直接重建（旧版本库自动清空，避免旧数据干扰）
        .fallbackToDestructiveMigration()
        .build()

    val apiClient = ApiClient(settingsRepository, secureTokenStore)

    val authRepository = AuthRepository(apiClient, secureTokenStore)

    val deviceRepository = DeviceRepository(apiClient, secureTokenStore, database.localDeviceDao())

    val serverRepository = ServerRepository(apiClient, settingsRepository)

    val spaceRepository = SpaceRepository(
        apiClient = apiClient,
        tokenStore = secureTokenStore,
        database = database,
        folderScanner = FolderScanner(context.applicationContext),
        pendingBindingStore = pendingBindingStore,
        spaceCoverStore = spaceCoverStore
    )

    val syncRepository = SyncRepository(
        apiClient = apiClient,
        tokenStore = secureTokenStore,
        database = database,
        spaceRepository = spaceRepository
    )

    val maintenanceRepository = MaintenanceRepository(
        apiClient = apiClient,
        tokenStore = secureTokenStore
    )

    val syncEngine = SyncEngine(
        repository = syncRepository,
        fileStore = SafFileStore(context.applicationContext)
    )

    val syncProgressNotifier = SyncProgressNotifier(
        context = context.applicationContext,
        repository = syncRepository,
        engine = syncEngine
    )

    val syncRealtimeClient = SyncRealtimeClient(
        context = context.applicationContext,
        apiClient = apiClient,
        repository = syncRepository
    )
}
