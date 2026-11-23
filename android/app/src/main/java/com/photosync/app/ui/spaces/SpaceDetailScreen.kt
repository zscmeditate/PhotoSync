package com.photosync.app.ui.spaces

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.remote.JoinRequestDto
import com.photosync.app.data.remote.SpaceMemberDto
import com.photosync.app.data.sync.SyncScheduler
import com.photosync.app.ui.components.ConfirmBottomSheet
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.components.SectionHeader
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.SuccessGreen
import com.photosync.app.util.formatIsoTime

@Composable
fun SpaceDetailScreen(
    spaceId: String,
    onBack: () -> Unit,
    onEditSpace: (String) -> Unit,
    onAddMapping: (String) -> Unit,
    onOpenMapping: (String) -> Unit,
    onOpenTrash: (String) -> Unit,
    onOpenConflicts: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val context = LocalContext.current
    val viewModel: SpaceDetailViewModel = viewModel(key = spaceId) {
        SpaceDetailViewModel(
            spaceRepository = app.container.spaceRepository,
            deviceRepository = app.container.deviceRepository,
            syncRepository = app.container.syncRepository,
            scheduleSync = { SyncScheduler.enqueue(app, force = true) },
            spaceId = spaceId
        )
    }
    val space by viewModel.space.collectAsStateWithLifecycle()
    val mappings by viewModel.mappings.collectAsStateWithLifecycle()
    val bindingPending by viewModel.bindingPending.collectAsStateWithLifecycle()
    val fileCounts by viewModel.fileCounts.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val joinRequests by viewModel.joinRequests.collectAsStateWithLifecycle()
    val resolvingRequestId by viewModel.resolvingRequestId.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val membershipChanging by viewModel.membershipChanging.collectAsStateWithLifecycle()
    val syncStats by viewModel.syncStats.collectAsStateWithLifecycle()
    val canManageMembers by viewModel.canManageMembers.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var changeTarget by remember { mutableStateOf<LocalFolderMapping?>(null) }
    var manageMembers by rememberSaveable { mutableStateOf(false) }
    var exitConfirm by rememberSaveable { mutableStateOf(false) }
    val thisDeviceIsMember = members.any { it.deviceId == viewModel.currentDeviceId }

    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val target = changeTarget
        changeTarget = null
        if (uri != null && target != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val label = DocumentFile.fromTreeUri(context, uri)?.name
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "已选目录"
                viewModel.changeDirectory(target, uri.toString(), label)
            } catch (e: SecurityException) {
                viewModel.showMessage("目录授权失败：系统未授予持久读写权限，请重新选择")
            } catch (e: Exception) {
                viewModel.showMessage("目录授权失败：${e.message ?: "无法读取所选目录"}")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpacePageHeader(
                title = space?.name ?: "空间详情",
                onBack = onBack,
                actions = {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新空间")
                        }
                    }
                    IconButton(onClick = { onEditSpace(spaceId) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑空间")
                    }
                }
            )

            if (space == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator() else Text("未找到该空间，请返回刷新")
                }
            } else {
                val latestScan = mappings.mapNotNull { it.lastScannedAt }.maxOrNull()
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (canManageMembers && joinRequests.isNotEmpty()) {
                        item { SectionHeader(title = "加入申请") }
                        items(joinRequests, key = { "join-${it.id}" }) { request ->
                            JoinRequestCard(
                                request = request,
                                resolving = resolvingRequestId == request.id,
                                onApprove = { viewModel.resolveJoinRequest(request.id, true) },
                                onReject = { viewModel.resolveJoinRequest(request.id, false) }
                            )
                        }
                    }
                    item {
                        Column {
                            when {
                                mappings.isNotEmpty() -> {
                                    PrimaryButton(
                                        text = if (syncStats.runningTasks > 0) "正在同步…" else "立即同步",
                                        onClick = viewModel::syncNow,
                                        enabled = syncStats.runningTasks == 0,
                                        leadingIcon = Icons.Filled.Sync
                                    )
                                    Text(
                                        text = "最近扫描：${formatIsoTime(latestScan)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceMuted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                    )
                                }
                                bindingPending -> {
                                    PrimaryButton(
                                        text = "等待主设备同意",
                                        onClick = viewModel::refresh,
                                        leadingIcon = Icons.Filled.HourglassEmpty
                                    )
                                    Text(
                                        text = "绑定申请已提交，同意后将自动完成目录绑定；点此刷新状态",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceMuted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                    )
                                }
                                else -> {
                                    PrimaryButton(
                                        text = "绑定本地目录",
                                        onClick = { onAddMapping(spaceId) },
                                        leadingIcon = Icons.Filled.Add
                                    )
                                    Text(
                                        text = "绑定目录后即可开始同步",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceMuted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = when {
                                        syncStats.failedTasks > 0 -> "${syncStats.failedTasks} 个同步任务需要处理"
                                        syncStats.runningTasks > 0 -> "正在执行持久化同步任务"
                                        syncStats.pendingUploads + syncStats.pendingDownloads > 0 -> "等待联网同步"
                                        else -> "同步任务已处理完成"
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (syncStats.failedTasks > 0) ErrorRed else PrimaryBlue
                                )
                                Text(
                                    text = "待上传 ${syncStats.pendingUploads} · 待下载 ${syncStats.pendingDownloads} · 失败 ${syncStats.failedTasks}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceMuted,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onOpenTrash(spaceId) },
                                modifier = Modifier.weight(1f)
                            ) { Text("回收站") }
                            OutlinedButton(
                                onClick = { onOpenConflicts(spaceId) },
                                modifier = Modifier.weight(1f)
                            ) { Text("冲突处理") }
                        }
                    }
                    item { SectionHeader(title = "本地目录") }
                    if (mappings.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                EmptyStateView(
                                    icon = Icons.Filled.Folder,
                                    title = "尚未绑定本地目录",
                                    subtitle = "点击上方「绑定本地目录」，该目录的结构会完整同步到空间"
                                )
                            }
                        }
                    } else {
                        items(mappings, key = { it.id }) { mapping ->
                            FolderMappingCard(
                                mapping = mapping,
                                fileCount = fileCounts.firstOrNull { it.mappingId == mapping.id }?.fileCount ?: 0,
                                onOpenFiles = { onOpenMapping(mapping.id) },
                                onChangeDirectory = {
                                    changeTarget = mapping
                                    directoryLauncher.launch(null)
                                }
                            )
                        }
                    }
                    item {
                        SectionHeader(
                            title = "参与设备",
                            actionText = when {
                                canManageMembers -> "管理"
                                thisDeviceIsMember -> "退出空间"
                                else -> null
                            },
                            onAction = when {
                                canManageMembers -> ({ manageMembers = true })
                                thisDeviceIsMember -> ({ exitConfirm = true })
                                else -> null
                            }
                        )
                    }
                    if (members.isEmpty()) {
                        item { Text("暂无成员信息，请刷新重试", color = OnSurfaceMuted) }
                    } else {
                        items(members, key = { it.deviceId }) { member ->
                            MemberRow(member = member, thisDeviceId = viewModel.currentDeviceId)
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (manageMembers) {
        MemberManageDialog(
            devices = devices,
            members = members,
            changingDeviceId = membershipChanging,
            onCheckedChange = viewModel::setMembership,
            onDismiss = { manageMembers = false }
        )
    }

    if (exitConfirm) {
        ConfirmBottomSheet(
            title = "退出空间",
            message = "确定让本机退出该空间吗？退出后本机不再参与同步。",
            confirmText = "退出空间",
            onConfirm = {
                viewModel.exitSpace()
                exitConfirm = false
            },
            onDismiss = { exitConfirm = false }
        )
    }
}

@Composable
private fun JoinRequestCard(
    request: JoinRequestDto,
    resolving: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Smartphone, contentDescription = null, tint = PrimaryBlue)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${request.deviceName} 申请加入空间",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "申请时间：${formatIsoTime(request.requestedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (resolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onReject, enabled = !resolving) { Text("拒绝") }
                TextButton(onClick = onApprove, enabled = !resolving) { Text("同意") }
            }
        }
    }
}

@Composable
private fun FolderMappingCard(
    mapping: LocalFolderMapping,
    fileCount: Int,
    onOpenFiles: () -> Unit,
    onChangeDirectory: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenFiles)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = PrimaryBlue)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = mapping.localPathLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MappingStatusChip(mapping.status)
            }
            Text(
                text = "空间根目录 · $fileCount 个文件 · ${if (mapping.onlyMedia) "仅照片和视频" else "全部文件"}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "上次扫描：${formatIsoTime(mapping.lastScannedAt)} · ${if (mapping.lastScanComplete) "完整" else "未完成"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (mapping.lastScanComplete) OnSurfaceMuted else ErrorRed,
                modifier = Modifier.padding(top = 3.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onChangeDirectory) { Text("更改目录") }
            }
        }
    }
}

@Composable
private fun MemberRow(member: SpaceMemberDto, thisDeviceId: String?) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Smartphone, contentDescription = null, tint = PrimaryBlue)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.deviceName + if (member.deviceId == thisDeviceId) "（本机）" else "",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${member.platform} · ${member.role} · 最近在线 ${formatIsoTime(member.lastSeenAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted
                )
            }
            Icon(
                imageVector = if (member.online) Icons.Filled.CheckCircle else Icons.Filled.CloudOff,
                contentDescription = null,
                tint = if (member.online) SuccessGreen else OnSurfaceMuted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (member.online) "在线" else "离线",
                style = MaterialTheme.typography.bodySmall,
                color = if (member.online) SuccessGreen else OnSurfaceMuted
            )
        }
    }
}

@Composable
private fun MemberManageDialog(
    devices: List<LocalDevice>,
    members: List<SpaceMemberDto>,
    changingDeviceId: String?,
    onCheckedChange: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理参与设备") },
        text = {
            Column {
                Text("勾选本账号下要加入空间的设备；取消勾选会将设备移出空间。", color = OnSurfaceMuted)
                Spacer(Modifier.height(12.dp))
                if (devices.isEmpty()) {
                    Text("设备列表为空，请先在设备管理页刷新或注册设备")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(devices, key = { it.id }) { device ->
                            val joined = members.any { it.deviceId == device.id }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = joined,
                                    onCheckedChange = { onCheckedChange(device.id, it) },
                                    enabled = changingDeviceId == null
                                )
                                Column {
                                    Text(device.deviceName + if (device.isThisDevice) "（本机）" else "")
                                    Text(
                                        if (changingDeviceId == device.id) "正在更新…" else device.platform,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}
