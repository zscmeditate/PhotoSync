package com.photosync.app.ui.spaces

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.ui.components.ConfirmBottomSheet
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.ErrorBanner
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.components.SecondaryButton
import com.photosync.app.ui.theme.PrimaryBlue

@Composable
fun SpaceListScreen(
    viewModel: SpaceListViewModel,
    onOpenSpace: (String) -> Unit,
    onCreateSpace: () -> Unit,
    onJoinSpace: () -> Unit,
    onEditSpace: (String) -> Unit
) {
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    val mappings by viewModel.mappings.collectAsStateWithLifecycle()
    val canCreateSpace by viewModel.canCreateSpace.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var abnormalOnly by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LocalSpace?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val abnormalSpaceIds = mappings.filter {
        it.status == com.photosync.app.data.local.MappingStatus.ERROR ||
            it.status == com.photosync.app.data.local.MappingStatus.ACCESS_REVOKED
    }.map { it.spaceId }.toSet()
    val filtered = if (abnormalOnly) spaces.filter { it.id in abnormalSpaceIds } else spaces

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpacePageHeader(
                title = "同步空间",
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新空间")
                    }
                    IconButton(onClick = onJoinSpace) {
                        Icon(Icons.Filled.GroupAdd, contentDescription = "加入空间")
                    }
                    if (canCreateSpace) {
                        IconButton(onClick = onCreateSpace) {
                            Icon(Icons.Filled.Add, contentDescription = "创建空间", tint = PrimaryBlue)
                        }
                    }
                }
            )
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = !abnormalOnly,
                    onClick = { abnormalOnly = false },
                    label = { Text("全部") }
                )
                FilterChip(
                    selected = abnormalOnly,
                    onClick = { abnormalOnly = true },
                    label = { Text("异常") }
                )
            }

            when {
                refreshing && spaces.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                filtered.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 20.dp)
                    ) {
                        loadError?.let {
                            ErrorBanner(message = it)
                            Spacer(Modifier.height(12.dp))
                        }
                        EmptyStateView(
                            icon = Icons.Filled.Folder,
                            title = if (abnormalOnly) "没有异常空间" else "暂无同步空间",
                            subtitle = if (abnormalOnly) {
                                "所有本机目录映射均无需处理"
                            } else if (canCreateSpace) {
                                "创建一个新空间，或加入同一账号下已有的空间，再选择本机目录"
                            } else {
                                "加入同一账号下已有的空间，再选择本机目录；创建空间需要使用主设备"
                            }
                        )
                        if (!abnormalOnly) {
                            if (canCreateSpace) {
                                PrimaryButton(
                                    text = "创建同步空间",
                                    onClick = onCreateSpace,
                                    leadingIcon = Icons.Filled.Add
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            SecondaryButton(
                                text = "加入已有空间",
                                onClick = onJoinSpace
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filtered, key = { it.id }) { space ->
                            SpaceCard(
                                space = space,
                                hasError = space.id in abnormalSpaceIds,
                                onClick = { onOpenSpace(space.id) },
                                onRename = { onEditSpace(space.id) },
                                onDelete = { deleteTarget = space }
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    deleteTarget?.let { space ->
        ConfirmBottomSheet(
            title = "删除整个同步空间",
            message = "确定删除「${space.name}」？这会删除服务器上的整个空间及其服务端文件，不只是解除本机关系；本机已存在的文件不会被删除。",
            confirmText = "删除服务器上的整个空间",
            onConfirm = { viewModel.deleteSpace(space.id) },
            onDismiss = { deleteTarget = null }
        )
    }
}
