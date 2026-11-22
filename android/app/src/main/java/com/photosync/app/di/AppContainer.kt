package com.photosync.app.di

import android.content.Context
import androidx.room.Room
import com.photosync.app.data.local.SyncDatabase
import com.photosync.app.data.local.SyncDatabaseMigrations
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
import com.photosync.app.data.sync.SyncRealtimeClient

/** 简单的手动依赖容器，随 Application 创建。 */
class AppContainer(context: Context) {

    val settingsRepository = SettingsRepository(context)

    val secureTokenStore = SecureTokenStore(context)

    val database: SyncDatabase = Room.databaseBuilder(
        context.applicationContext,
        SyncDatabase::class.java,
        "photosync.db"
    )
        .addMigrations(
            SyncDatabaseMigrations.MIGRATION_1_2,
            SyncDatabaseMigrations.MIGRATION_2_3,
            SyncDatabaseMigrations.MIGRATION_3_4
        )
        .build()

    val apiClient = ApiClient(settingsRepository, secureTokenStore)

    val authRepository = AuthRepository(apiClient, secureTokenStore)

    val deviceRepository = DeviceRepository(apiClient, secureTokenStore, database.localDeviceDao())

    val serverRepository = ServerRepository(apiClient, settingsRepository)

    val spaceRepository = SpaceRepository(
        apiClient = apiClient,
        tokenStore = secureTokenStore,
        database = database,
        folderScanner = FolderScanner(context.applicationContext)
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

    val syncRealtimeClient = SyncRealtimeClient(
        context = context.applicationContext,
        apiClient = apiClient,
        repository = syncRepository
    )
}
