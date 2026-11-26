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
        val processStartedAt = System.currentTimeMillis()
        container = AppContainer(this)
        container.syncProgressNotifier.start()
        SyncScheduler.enqueue(this)
        appScope.launch {
            // 上次进程被杀时中断的传输不会继续，退回队列，避免界面把陈旧任务显示成「进行中」
            container.syncRepository.recoverStaleRunning(processStartedAt)
            SyncScheduler.reschedule(this@PhotoSyncApp)
        }
        container.syncRealtimeClient.start()
    }
}
