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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.sync.SyncScheduler
import com.photosync.app.ui.components.AppSnackbarHost
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
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    val pendingSpaces by viewModel.pendingSpaces.collectAsStateWithLifecycle()
    // 已加入的空间在上，本机尚未加入的空间排在其后，进入详情即可申请加入
    val displaySpaces = spaces + pendingSpaces
    val mappings by viewModel.mappings.collectAsStateWithLifecycle()
    val joinedSpaceIds by remember(spaces) {
        androidx.compose.runtime.derivedStateOf { spaces.filter { it.role != "none" }.map { it.id }.toSet() }
    }
    val canCreateSpace by viewModel.canCreateSpace.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val membersBySpace by viewModel.membersBySpace.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val canManageMembers by viewModel.canManageMembers.collectAsStateWithLifecycle()
    val membershipChanging by viewModel.membershipChanging.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<LocalSpace?>(null) }
    var manageSpaceId by remember { mutableStateOf<String?>(null) }
    var exitTarget by remember { mutableStateOf<LocalSpace?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val abnormalSpaceIds = mappings.filter {
        it.status == com.photosync.app.data.local.MappingStatus.ERROR ||
            it.status == com.photosync.app.data.local.MappingStatus.ACCESS_REVOKED
    }.map { it.spaceId }.toSet()

    val hasJoinedSpaces = joinedSpaceIds.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpacePageHeader(
                title = "同步空间",
                actions = {
                    IconButton(onClick = { viewModel.refresh(force = true) }) {
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
            when {
                refreshing && displaySpaces.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                displaySpaces.isEmpty() -> {
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
                            title = "暂无同步空间",
                            subtitle = if (canCreateSpace) {
                                "创建一个新空间，或加入同一账号下已有的空间，再选择本机目录"
                            } else {
                                "加入同一账号下已有的空间，再选择本机目录；创建空间需要使用主设备"
                            }
                        )
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
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (hasJoinedSpaces) {
                            item {
                                PrimaryButton(
                                    text = "立即同步所有空间",
                                    onClick = {
                                        viewModel.syncAll { SyncScheduler.enqueue(app, force = true, spaceId = null) }
                                    },
                                    leadingIcon = Icons.Filled.Sync
                                )
                            }
                        }
                        items(displaySpaces, key = { it.id }) { space ->
                            val joined = space.role != "none"
                            SpaceCard(
                                space = space,
                                hasError = space.id in abnormalSpaceIds,
                                members = membersBySpace[space.id].orEmpty(),
                                showParticipants = joined,
                                thisDeviceId = viewModel.currentDeviceId,
                                canManageMembers = canManageMembers && joined,
                                onManageMembers = if (joined) ({ manageSpaceId = space.id }) else null,
                                onExitSpace = if (joined) ({ exitTarget = space }) else null,
                                onClick = { onOpenSpace(space.id) },
                                onRename = if (joined) ({ onEditSpace(space.id) }) else null,
                                onDelete = if (joined) ({ deleteTarget = space }) else null
                            )
                        }
                    }
                }
            }
        }
        AppSnackbarHost(hostState = snackbarHostState)
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

    manageSpaceId?.let { spaceId ->
        MemberManageDialog(
            devices = devices,
            members = membersBySpace[spaceId].orEmpty(),
            changingDeviceId = membershipChanging,
            onCheckedChange = { deviceId, join -> viewModel.setMembership(spaceId, deviceId, join) },
            onDismiss = { manageSpaceId = null }
        )
    }

    exitTarget?.let { space ->
        ConfirmBottomSheet(
            title = "退出空间",
            message = "确定让本机退出「${space.name}」？本机目录绑定会被解除，云端文件不受影响。",
            confirmText = "退出空间",
            onConfirm = {
                viewModel.currentDeviceId?.let { viewModel.setMembership(space.id, it, false) }
                exitTarget = null
            },
            onDismiss = { exitTarget = null }
        )
    }
}
