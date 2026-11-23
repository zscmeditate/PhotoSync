package com.photosync.app.ui.conflicts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.photosync.app.data.remote.ConflictDto
import com.photosync.app.data.sync.SyncScheduler
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.ErrorBanner
import com.photosync.app.ui.spaces.SpacePageHeader
import com.photosync.app.ui.spaces.formatFileSize
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.SuccessGreen
import com.photosync.app.ui.theme.WarningOrange
import com.photosync.app.util.formatIsoTime

/** 冲突处理：查看服务器版本与提交版本的差异，并选择解决方式。 */
@Composable
fun ConflictsScreen(spaceId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: ConflictsViewModel = viewModel(key = "conflicts-$spaceId") {
        ConflictsViewModel(
            repository = app.container.maintenanceRepository,
            spaceId = spaceId,
            onResolved = { SyncScheduler.enqueue(app) }
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<Pair<ConflictDto, String>?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpacePageHeader(
                title = "冲突处理",
                onBack = onBack,
                actions = {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新冲突")
                        }
                    }
                }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "同一文件被多台设备同时修改时会产生冲突。选择保留服务器版本、采纳本机提交版本，或两者都保留（生成冲突副本）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted
                    )
                }
                if (state.error != null) {
                    item { ErrorBanner(state.error!!) }
                }
                if (!state.loading && state.conflicts.isEmpty() && state.error == null) {
                    item {
                        EmptyStateView(
                            icon = Icons.Filled.SyncProblem,
                            title = "没有冲突",
                            subtitle = "多设备修改冲突会显示在这里"
                        )
                    }
                } else {
                    items(state.conflicts, key = { it.conflictId }) { conflict ->
                        ConflictCard(
                            conflict = conflict,
                            busy = state.resolvingId == conflict.conflictId,
                            onResolve = { resolution -> pending = conflict to resolution }
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    pending?.let { (conflict, resolution) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(resolutionTitle(resolution)) },
            text = { Text(resolutionMessage(resolution, conflict)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resolve(conflict, resolution)
                        pending = null
                    }
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ConflictCard(
    conflict: ConflictDto,
    busy: Boolean,
    onResolve: (String) -> Unit
) {
    val pending = conflict.status == "pending"
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SyncProblem,
                    contentDescription = null,
                    tint = if (pending) WarningOrange else SuccessGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = conflict.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (pending) "待处理" else "已解决",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pending) WarningOrange else SuccessGreen,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = conflict.relativePath,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "服务器版本 ${conflict.serverVersion} · 本机提交 " +
                    formatFileSize(conflict.submitted.size.toLongOrNull() ?: 0L),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = if (conflict.resolvedAt != null) {
                    "产生于 ${formatIsoTime(conflict.createdAt)} · 解决于 ${formatIsoTime(conflict.resolvedAt)}"
                } else {
                    "产生于 ${formatIsoTime(conflict.createdAt)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (pending) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    TextButton(onClick = { onResolve("server") }, enabled = !busy) {
                        Text("保留服务器版本（丢弃本机提交）", color = PrimaryBlue)
                    }
                    TextButton(onClick = { onResolve("client") }, enabled = !busy) {
                        Text("采纳本机提交（覆盖服务器版本）", color = PrimaryBlue)
                    }
                    TextButton(onClick = { onResolve("both") }, enabled = !busy) {
                        Text("两者都保留（生成冲突副本）", color = PrimaryBlue)
                    }
                }
            }
            if (!pending && conflict.status == "resolved") {
                Text(
                    text = "已按所选方式处理，可重新同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun resolutionTitle(resolution: String): String = when (resolution) {
    "server" -> "保留服务器版本"
    "client" -> "采纳本机提交"
    else -> "两者都保留"
}

private fun resolutionMessage(resolution: String, conflict: ConflictDto): String = when (resolution) {
    "server" -> "「${conflict.fileName}」将保留服务器上的版本 ${conflict.serverVersion}，本机提交的内容会被丢弃。"
    "client" -> "「${conflict.fileName}」将使用本机提交的内容覆盖服务器版本 ${conflict.serverVersion}。"
    else -> "「${conflict.fileName}」会保留服务器版本，并将本机提交保存为冲突副本，两种内容都不会丢失。"
}
