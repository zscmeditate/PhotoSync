package com.photosync.app

import android.app.Application
import com.photosync.app.di.AppContainer
import com.photosync.app.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhotoSyncApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.enqueue(this)
        appScope.launch { SyncScheduler.reschedule(this@PhotoSyncApp) }
        container.syncRealtimeClient.start()
    }
}
