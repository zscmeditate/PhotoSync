package com.photosync.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.data.local.SyncTaskState
import com.photosync.app.data.sync.SyncScheduler
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.spaces.SpacePageHeader
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue

private enum class TaskFilter(val label: String) {
    ALL("全部"),
    RUNNING("进行中"),
    FAILED("失败")
}

/** 任务中心：本机持久化同步任务的执行状态、错误、重试与取消。 */
@Composable
fun TaskCenterScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: SyncTasksViewModel = viewModel {
        SyncTasksViewModel(app.container.syncRepository) { SyncScheduler.enqueue(app, force = true) }
    }
    val tasks by viewModel.active.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(TaskFilter.ALL) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val runningCount = tasks.count { it.state == SyncTaskState.RUNNING }
    val failedCount = tasks.count { it.state == SyncTaskState.FAILED }
    val visible = when (filter) {
        TaskFilter.ALL -> tasks
        TaskFilter.RUNNING -> tasks.filter { it.state == SyncTaskState.RUNNING }
        TaskFilter.FAILED -> tasks.filter { it.state == SyncTaskState.FAILED }
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpacePageHeader(
                title = "任务中心",
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::syncNow) {
                        Icon(Icons.Filled.Refresh, contentDescription = "立即同步")
                    }
                }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = when {
                                    runningCount > 0 -> "正在执行 $runningCount 个任务"
                                    failedCount > 0 -> "$failedCount 个任务失败，可重试"
                                    tasks.isEmpty() -> "暂无待处理任务"
                                    else -> "任务已全部处理完成"
                                },
                                fontWeight = FontWeight.SemiBold,
                                color = if (failedCount > 0) {
                                    com.photosync.app.ui.theme.ErrorRed
                                } else {
                                    PrimaryBlue
                                }
                            )
                            Text(
                                text = "待处理 ${tasks.size} · 进行中 $runningCount · 失败 $failedCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceMuted,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            if (failedCount > 0) {
                                TextButton(
                                    onClick = viewModel::retryAllFailed,
                                    contentPadding = PaddingValues(horizontal = 0.dp)
                                ) {
                                    Text("全部重试")
                                }
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskFilter.entries.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item {
                        EmptyStateView(
                            icon = Icons.Filled.Sync,
                            title = "暂无任务",
                            subtitle = "同步任务会持久化在本机，失败后可在此重试"
                        )
                    }
                } else {
                    items(visible, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onRetry = { viewModel.retry(task) },
                            onCancel = { viewModel.cancel(task) }
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TaskCard(
    task: LocalSyncTask,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TaskPresentation.typeIcon(task.type),
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = TaskPresentation.displayName(task.remoteRelativePath),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = TaskPresentation.stateIcon(task.state),
                    contentDescription = null,
                    tint = TaskPresentation.stateColor(task.state),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = TaskPresentation.stateLabel(task.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = TaskPresentation.stateColor(task.state),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "${TaskPresentation.typeLabel(task.type)} · ${task.remoteRelativePath}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "更新于 ${TaskPresentation.formatTime(task.updatedAt)} · 尝试 ${task.attempt} 次",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (task.state == SyncTaskState.RUNNING && task.size > 0) {
                LinearProgressIndicator(
                    progress = { (task.bytesTransferred.toFloat() / task.size.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
            val error = TaskPresentation.errorText(task.error)
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = com.photosync.app.ui.theme.ErrorRed,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (TaskPresentation.canRetry(task.state) || TaskPresentation.canCancel(task.state)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (TaskPresentation.canCancel(task.state)) {
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                    if (TaskPresentation.canRetry(task.state)) {
                        TextButton(onClick = onRetry) { Text("重试", color = PrimaryBlue) }
                    }
                }
            }
        }
    }
}
