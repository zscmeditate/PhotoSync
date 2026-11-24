package com.photosync.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.data.repository.SyncRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 活动记录 ViewModel：观察最近任务时间线（含已完成任务）；spaceId 非空时仅该空间的记录。 */
class SyncTasksViewModel(
    private val repository: SyncRepository,
    val spaceId: String? = null
) : ViewModel() {

    val recent: StateFlow<List<LocalSyncTask>> =
        if (spaceId == null) {
            repository.observeRecentTasks(200)
        } else {
            repository.observeRecentTasks(spaceId, 200)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** 清空同步日志（spaceId 非空时只清该空间，否则清全部）。 */
    fun clearLog() {
        viewModelScope.launch {
            val n = if (spaceId != null) {
                repository.clearTasksBySpace(spaceId)
            } else {
                repository.clearAllTasks()
            }
            _messages.send(if (n > 0) "已清空 $n 条日志" else "没有可清空的日志")
        }
    }
}
