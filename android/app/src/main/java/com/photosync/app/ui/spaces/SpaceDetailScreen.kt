package com.photosync.app.ui.spaces

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.remote.SpaceMemberDto
import com.photosync.app.data.sync.FolderScanProgress
import com.photosync.app.data.sync.RemotePrefix
import com.photosync.app.data.sync.SyncScheduler
import com.photosync.app.ui.components.ConfirmBottomSheet
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.components.SectionHeader
import com.photosync.app.ui.components.appTextFieldColors
import com.photosync.app.ui.theme.AccentCyan
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
    val viewModel: SpaceDetailViewModel = viewModel(key = spaceId) {
        SpaceDetailViewModel(
            spaceRepository = app.container.spaceRepository,
            deviceRepository = app.container.deviceRepository,
            syncRepository = app.container.syncRepository,
            scheduleSync = { SyncScheduler.enqueue(app) },
            spaceId = spaceId
        )
    }
    val space by viewModel.space.collectAsStateWithLifecycle()
    val mappings by viewModel.mappings.collectAsStateWithLifecycle()
    val fileCounts by viewModel.fileCounts.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val membershipChanging by viewModel.membershipChanging.collectAsStateWithLifecycle()
    val scanningIds by viewModel.scanningIds.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val syncStats by viewModel.syncStats.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editMapping by remember { mutableStateOf<LocalFolderMapping?>(null) }
    var deleteMapping by remember { mutableStateOf<LocalFolderMapping?>(null) }
    var manageMembers by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                val currentSpace = space!!
                val latestScan = mappings.mapNotNull { it.lastScannedAt }.maxOrNull()
                val hasError = syncStats.failedTasks > 0 || mappings.any {
                    it.status == MappingStatus.ERROR || it.status == MappingStatus.ACCESS_REVOKED
                }
                val scanning = mappings.any { it.status == MappingStatus.SCANNING }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.horizontalGradient(listOf(PrimaryBlue, AccentCyan)))
                                .padding(20.dp)
                        ) {
                            Text(
                                text = when {
                                    hasError -> "部分目录需要处理"
                                    scanning -> "正在扫描本地目录"
                                    mappings.isEmpty() -> "空间待配置"
                                    else -> "本地索引已就绪"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "${currentSpace.memberCount} 台设备 · ${currentSpace.mappingCount} 个目录映射",
                                color = Color.White.copy(alpha = 0.94f)
                            )
                            Text(
                                text = "最近扫描：${formatIsoTime(latestScan)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.88f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    item {
                        PrimaryButton(
                            text = if (syncStats.runningTasks > 0) "正在同步…" else "立即同步",
                            onClick = viewModel::syncNow,
                            enabled = syncStats.runningTasks == 0,
                            leadingIcon = Icons.Filled.Sync
                        )
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
                        PrimaryButton(
                            text = "添加同步目录",
                            onClick = { onAddMapping(spaceId) },
                            leadingIcon = Icons.Filled.Add
                        )
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
                    item { SectionHeader(title = "本地目录映射") }
                    if (mappings.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                EmptyStateView(
                                    icon = Icons.Filled.Folder,
                                    title = "尚未选择本机目录",
                                    subtitle = "点击上方「添加同步目录」，通过系统文件选择器授权"
                                )
                            }
                        }
                    } else {
                        items(mappings, key = { it.id }) { mapping ->
                            FolderMappingCard(
                                mapping = mapping,
                                fileCount = fileCounts.firstOrNull { it.mappingId == mapping.id }?.fileCount ?: 0,
                                scanning = scanningIds.contains(mapping.id),
                                progress = scanProgress[mapping.id],
                                onOpenFiles = { onOpenMapping(mapping.id) },
                                onRescan = { viewModel.rescan(mapping.id) },
                                onEdit = { editMapping = mapping },
                                onDelete = { deleteMapping = mapping }
                            )
                        }
                    }
                    item {
                        SectionHeader(
                            title = "参与设备",
                            actionText = "管理",
                            onAction = { manageMembers = true }
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

    editMapping?.let { mapping ->
        EditMappingDialog(
            mapping = mapping,
            siblings = mappings,
            onDismiss = { editMapping = null },
            onConfirm = { prefix, include, onlyMedia ->
                viewModel.updateMapping(mapping, prefix, include, onlyMedia)
                editMapping = null
            }
        )
    }

    deleteMapping?.let { mapping ->
        ConfirmBottomSheet(
            title = "删除目录映射",
            message = "确定删除「${mapping.localPathLabel}」的映射？不会删除本地文件，也不会把本次操作当成本地文件删除。",
            confirmText = "删除映射",
            onConfirm = { viewModel.deleteMapping(mapping.id) },
            onDismiss = { deleteMapping = null }
        )
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
}

@Composable
private fun FolderMappingCard(
    mapping: LocalFolderMapping,
    fileCount: Int,
    scanning: Boolean,
    progress: FolderScanProgress?,
    onOpenFiles: () -> Unit,
    onRescan: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
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
                text = "远端：/${mapping.remotePrefix}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "$fileCount 个文件 · ${if (mapping.includeSubdirs) "包含子目录" else "仅当前目录"} · ${if (mapping.onlyMedia) "仅照片和视频" else "全部文件"}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "上次扫描：${formatIsoTime(mapping.lastScannedAt)} · ${if (mapping.lastScanComplete) "完整" else "未完成"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (mapping.lastScanComplete) OnSurfaceMuted else ErrorRed,
                modifier = Modifier.padding(top = 3.dp)
            )
            if (scanning && progress != null) {
                Text(
                    text = "已扫描 ${progress.scannedCount} 个文件 · ${progress.currentFileName.ifEmpty { "正在准备…" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrimaryBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRescan, enabled = !scanning) {
                    Text(if (scanning) "扫描中…" else "重新扫描")
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除", color = ErrorRed) }
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
private fun EditMappingDialog(
    mapping: LocalFolderMapping,
    siblings: List<LocalFolderMapping>,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean, Boolean) -> Unit
) {
    var prefix by rememberSaveable(mapping.id) { mutableStateOf(mapping.remotePrefix) }
    var includeSubdirs by rememberSaveable(mapping.id) { mutableStateOf(mapping.includeSubdirs) }
    var onlyMedia by rememberSaveable(mapping.id) { mutableStateOf(mapping.onlyMedia) }
    val validation = RemotePrefix.validate(prefix)
    val overlap = siblings.any {
        it.id != mapping.id && RemotePrefix.overlaps(it.remotePrefix, validation.normalized)
    }
    val error = validation.error ?: if (overlap) "该路径与同空间的本机已有映射重叠" else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑目录映射") },
        text = {
            Column {
                Text(mapping.localPathLabel, color = OnSurfaceMuted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("同步空间内路径") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "远端预览：/${validation.normalized}（空路径表示空间根）")
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("包含子目录", modifier = Modifier.weight(1f))
                    Switch(checked = includeSubdirs, onCheckedChange = { includeSubdirs = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("仅同步照片和视频", modifier = Modifier.weight(1f))
                    Switch(checked = onlyMedia, onCheckedChange = { onlyMedia = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(validation.normalized, includeSubdirs, onlyMedia) }, enabled = error == null) {
                Text("保存", color = PrimaryBlue)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
