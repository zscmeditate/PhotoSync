package com.photosync.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.data.local.SyncTaskState
import com.photosync.app.data.repository.SyncRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 任务中心与活动记录共用的 ViewModel：观察本地持久化任务并提供重试/取消。 */
class SyncTasksViewModel(
    private val repository: SyncRepository,
    private val scheduleSync: () -> Unit
) : ViewModel() {

    val active: StateFlow<List<LocalSyncTask>> = repository.observeActiveTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<LocalSyncTask>> = repository.observeRecentTasks(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun retry(task: LocalSyncTask) {
        viewModelScope.launch {
            repository.retryTask(task.id)
            scheduleSync()
            _messages.emit("已重新排队：${TaskPresentation.displayName(task.remoteRelativePath)}")
        }
    }

    fun cancel(task: LocalSyncTask) {
        viewModelScope.launch {
            repository.cancelTask(task.id)
            _messages.emit("已取消：${TaskPresentation.displayName(task.remoteRelativePath)}")
        }
    }

    fun retryAllFailed() {
        viewModelScope.launch {
            val failed = active.value.filter { it.state == SyncTaskState.FAILED }
            failed.forEach { repository.retryTask(it.id) }
            if (failed.isEmpty()) {
                _messages.emit("没有失败任务")
            } else {
                scheduleSync()
                _messages.emit("已重新排队 ${failed.size} 个失败任务")
            }
        }
    }

    fun syncNow() {
        scheduleSync()
    }
}
