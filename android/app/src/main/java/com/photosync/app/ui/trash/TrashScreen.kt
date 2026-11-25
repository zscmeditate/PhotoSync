package com.photosync.app.ui.trash

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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.photosync.app.data.remote.TrashItemDto
import com.photosync.app.ui.components.AppSnackbarHost
import com.photosync.app.ui.components.ConfirmBottomSheet
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.ErrorBanner
import com.photosync.app.ui.spaces.SpacePageHeader
import com.photosync.app.ui.spaces.formatFileSize
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.util.formatIsoTime

/** 空间回收站：列出服务端 Tombstone，可恢复或彻底清理。 */
@Composable
fun TrashScreen(spaceId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: TrashViewModel = viewModel(key = "trash-$spaceId") {
        TrashViewModel(app.container.spaceRepository, app.container.maintenanceRepository, spaceId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canClearTrash by viewModel.canClearTrash.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var purgeTarget by remember { mutableStateOf<TrashItemDto?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpacePageHeader(
                title = "回收站",
                onBack = onBack,
                actions = {
                    if (state.loading || state.clearing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("刷新") },
                                    onClick = {
                                        showMenu = false
                                        viewModel.refresh()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("清空回收站") },
                                    enabled = canClearTrash && state.items.isNotEmpty(),
                                    onClick = {
                                        showMenu = false
                                        showClearAllConfirm = true
                                    }
                                )
                            }
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
                        text = "删除的文件会在服务端保留一段时间，期间可恢复；彻底删除后不可恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted
                    )
                }
                if (state.error != null) {
                    item { ErrorBanner(state.error!!) }
                }
                if (!state.loading && state.items.isEmpty() && state.error == null) {
                    item {
                        EmptyStateView(
                            icon = Icons.Filled.DeleteSweep,
                            title = "回收站为空",
                            subtitle = "各设备删除的文件会出现在这里，可在保留期内恢复"
                        )
                    }
                } else {
                    items(state.items, key = { it.trashId }) { item ->
                        TrashItemCard(
                            item = item,
                            busy = state.actionTrashId == item.trashId,
                            onRestore = { viewModel.restore(item) },
                            onPurge = { purgeTarget = item }
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
        AppSnackbarHost(hostState = snackbarHostState)
    }

    purgeTarget?.let { item ->
        ConfirmBottomSheet(
            title = "彻底删除",
            message = "「${item.fileName}」将从服务器永久删除，无法恢复。",
            confirmText = "彻底删除",
            onConfirm = { viewModel.purge(item) },
            onDismiss = { purgeTarget = null }
        )
    }

    if (showClearAllConfirm) {
        ConfirmBottomSheet(
            title = "清空回收站",
            message = "回收站内 ${state.items.size} 个文件将被永久删除，无法恢复。",
            confirmText = "清空",
            onConfirm = { viewModel.purgeAll() },
            onDismiss = { showClearAllConfirm = false }
        )
    }
}

@Composable
private fun TrashItemCard(
    item: TrashItemDto,
    busy: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = item.relativePath,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "${formatFileSize(item.size.toLongOrNull() ?: 0L)} · 版本 ${item.version} · " +
                    "删除于 ${formatIsoTime(item.deletedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = "保留至 ${formatIsoTime(item.purgeAfter)}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onPurge, enabled = !busy) {
                    Text("彻底删除", color = ErrorRed)
                }
                TextButton(onClick = onRestore, enabled = !busy) {
                    Icon(Icons.Filled.Restore, contentDescription = null, tint = PrimaryBlue)
                    Spacer(Modifier.width(4.dp))
                    Text(if (busy) "处理中…" else "恢复", color = PrimaryBlue)
                }
            }
        }
    }
}
