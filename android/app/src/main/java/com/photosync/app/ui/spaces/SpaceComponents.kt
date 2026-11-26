package com.photosync.app.ui.spaces

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photosync.app.data.local.FileSyncStatus
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.remote.SpaceMemberDto
import com.photosync.app.data.sync.FileFailureInfo
import com.photosync.app.ui.components.IconBadge
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.SuccessGreen
import com.photosync.app.ui.theme.WarningOrange
import com.photosync.app.util.formatIsoTime
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SpacePageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack == null) 20.dp else 4.dp,
                end = if (onBack == null) 20.dp else 4.dp,
                bottom = 12.dp
            )
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        actions()
    }
}

/** 空间头像：有自定义照片时显示圆角矩形缩略图，否则沿用默认文件夹图标。 */
@Composable
private fun SpaceAvatar(coverPath: String?, size: Int) {
    val bitmap = remember(coverPath) {
        coverPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "空间头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        IconBadge(icon = Icons.Filled.Folder, size = size)
    }
}

@Composable
fun SpaceCard(
    space: LocalSpace,
    onClick: () -> Unit,
    hasError: Boolean = false,
    fileCount: Int? = null,
    onChangeCover: (() -> Unit)? = null,
    onRemoveCover: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpaceAvatar(coverPath = space.coverPath, size = 46)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = space.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (fileCount != null) {
                            Text(
                                text = "（$fileCount）",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceMuted,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        RoleBadge(space.role)
                    }
                    Text(
                        text = "${space.memberCount} 台设备 · ${space.mappingCount} 台已绑定目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (space.role == "none") {
                        Text(
                            text = "本机未加入 · 点击进入后申请加入",
                            style = MaterialTheme.typography.bodySmall,
                            color = PrimaryBlue,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    if (hasError) {
                        Text(
                            text = "有目录映射需要处理",
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorRed,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
                if (onChangeCover != null || onRename != null || onDelete != null) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "空间操作")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("进入详情") },
                                onClick = {
                                    menuExpanded = false
                                    onClick()
                                }
                            )
                            if (onChangeCover != null) {
                                DropdownMenuItem(
                                    text = { Text(if (space.coverVersion == null) "设置头像" else "更换头像") },
                                    leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onChangeCover()
                                    }
                                )
                            }
                            if (onRemoveCover != null && space.coverVersion != null) {
                                DropdownMenuItem(
                                    text = { Text("移除头像") },
                                    leadingIcon = { Icon(Icons.Filled.HideImage, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onRemoveCover()
                                    }
                                )
                            }
                            if (onRename != null) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    enabled = space.role == "admin" || space.role == "owner",
                                    onClick = {
                                        menuExpanded = false
                                        onRename()
                                    }
                                )
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text("删除整个空间") },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    enabled = space.role == "admin",
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 空间详情的参与设备 Tab：设备列表与管理/退出入口按角色显示。 */
@Composable
fun ParticipantDevicesCard(
    members: List<SpaceMemberDto>,
    thisDeviceId: String?,
    canManageMembers: Boolean,
    onManageMembers: (() -> Unit)?,
    onExitSpace: (() -> Unit)?
) {
    val thisDeviceIsMember = members.any { it.deviceId == thisDeviceId }
    val actionText = when {
        canManageMembers && onManageMembers != null -> "管理"
        thisDeviceIsMember && onExitSpace != null -> "退出空间"
        else -> null
    }
    val action = when {
        canManageMembers && onManageMembers != null -> onManageMembers
        thisDeviceIsMember && onExitSpace != null -> onExitSpace
        else -> null
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 8.dp)) {
            if (action != null && actionText != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = action,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(actionText, style = MaterialTheme.typography.labelMedium, color = PrimaryBlue)
                    }
                }
            }
            if (members.isEmpty()) {
                Text(
                    text = "暂无成员信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                members.forEach { member ->
                    MemberSubRow(member = member, thisDeviceId = thisDeviceId)
                }
            }
        }
    }
}

/** 子卡片内单台参与设备：名称 + 角色 + 在线状态，单行紧凑展示。 */
@Composable
private fun MemberSubRow(member: SpaceMemberDto, thisDeviceId: String?) {
    // 本机正在运行 App 即为在线，不依赖服务端心跳时间
    val online = member.online || member.deviceId == thisDeviceId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Smartphone,
            contentDescription = null,
            tint = PrimaryBlue,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = member.deviceName + if (member.deviceId == thisDeviceId) "（本机）" else "",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = memberRoleLabel(member.role),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (online) Icons.Filled.CheckCircle else Icons.Filled.CloudOff,
            contentDescription = null,
            tint = if (online) SuccessGreen else OnSurfaceMuted,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = if (online) "在线" else "离线",
            style = MaterialTheme.typography.bodySmall,
            color = if (online) SuccessGreen else OnSurfaceMuted
        )
    }
}

/** 参与设备管理弹层：勾选本账号下的设备加入/移出空间。 */
@Composable
fun MemberManageDialog(
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

private fun memberRoleLabel(role: String): String = when (role) {
    "owner" -> "所有者"
    "admin" -> "管理员"
    "member" -> "成员"
    "viewer" -> "只读"
    else -> role
}

@Composable
private fun RoleBadge(role: String) {
    val text = when (role) {
        "owner" -> "所有者"
        "admin" -> "管理员"
        "member" -> "成员"
        "viewer" -> "只读"
        "none" -> "未加入"
        else -> role
    }
    Text(
        text = text,
        fontSize = 11.sp,
        lineHeight = 12.sp,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        color = PrimaryBlue,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(PrimaryBlue.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun StatusChip(text: String, icon: ImageVector, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.11f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MappingStatusChip(status: String) {
    when (status) {
        MappingStatus.ACTIVE -> StatusChip("正常", Icons.Filled.CheckCircle, SuccessGreen)
        MappingStatus.SCANNING -> StatusChip("扫描中", Icons.Filled.Sync, PrimaryBlue)
        MappingStatus.ACCESS_REVOKED -> StatusChip("授权失效", Icons.Filled.ErrorOutline, ErrorRed)
        else -> StatusChip("扫描异常", Icons.Filled.SyncProblem, ErrorRed)
    }
}

@Composable
fun FileStatusChip(status: String) {
    when (status) {
        FileSyncStatus.PENDING_UPLOAD -> StatusChip("待上传", Icons.Filled.CloudUpload, PrimaryBlue)
        FileSyncStatus.PENDING_DOWNLOAD -> StatusChip("待下载", Icons.Filled.CloudDownload, PrimaryBlue)
        FileSyncStatus.SYNCED -> StatusChip("已同步", Icons.Filled.CheckCircle, SuccessGreen)
        FileSyncStatus.CONFLICT -> StatusChip("冲突", Icons.Filled.SyncProblem, WarningOrange)
        FileSyncStatus.LOCAL_ONLY -> StatusChip("仅本地", Icons.Filled.Info, OnSurfaceMuted)
        FileSyncStatus.SYNC_ERROR -> StatusChip("同步失败", Icons.Filled.ErrorOutline, ErrorRed)
        else -> StatusChip(status, Icons.Filled.Info, OnSurfaceMuted)
    }
}

fun formatFileSize(size: Long): String {
    if (size < 1024) return "$size B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = size.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

fun formatModifiedAt(timestamp: Long): String {
    if (timestamp <= 0L) return "修改时间未知"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}

/** 失败详情弹层的 UI 状态（本地目录页与空间详情任务列表共用）。 */
data class FailureDetailUi(
    val file: LocalFileIndex,
    val loading: Boolean,
    val info: FileFailureInfo? = null
)

/** 同步失败详情弹层：云端/本地文件信息 + 本地优先/云端优先/全部删除/重试 操作行；任务场景可附加右上角取消。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FailureDetailSheet(
    detail: FailureDetailUi,
    busy: Boolean,
    onKeepLocal: () -> Unit,
    onKeepCloud: () -> Unit,
    onDeleteBoth: () -> Unit,
    onDismiss: () -> Unit,
    canResolve: Boolean = true,
    onRetry: (() -> Unit)? = null,
    onCancelTask: (() -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = detail.file.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = detail.file.relativePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (onCancelTask != null) {
                    TextButton(
                        onClick = onCancelTask,
                        enabled = !busy
                    ) {
                        Text("取消任务")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (detail.loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                val info = detail.info
                DetailInfoRow(
                    label = "云端文件",
                    value = when {
                        info?.cloud != null -> {
                            val created = info.cloud.createdAtIso?.let { " · ${formatIsoTime(it)}" } ?: ""
                            "${formatFileSize(info.cloud.size)} · 版本 v${info.cloud.version}$created"
                        }
                        info?.cloudError != null -> "加载失败：${info.cloudError}"
                        else -> "云端暂无文件"
                    }
                )
                DetailInfoRow(
                    label = "本地文件",
                    value = if (info?.localSize != null) {
                        "${formatFileSize(info.localSize)} · ${formatModifiedAt(info.localModifiedAt ?: 0L)}"
                    } else {
                        "本地文件不存在"
                    }
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onKeepLocal,
                        enabled = !busy && canResolve,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "本地优先",
                            color = if (canResolve) PrimaryBlue else OnSurfaceMuted,
                            maxLines = 1
                        )
                    }
                    TextButton(
                        onClick = onKeepCloud,
                        enabled = !busy && info?.cloud != null && canResolve,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "云端优先",
                            color = if (info?.cloud != null && canResolve) PrimaryBlue else OnSurfaceMuted,
                            maxLines = 1
                        )
                    }
                    TextButton(
                        onClick = onDeleteBoth,
                        enabled = !busy && canResolve,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "全部删除",
                            color = if (canResolve) ErrorRed else OnSurfaceMuted,
                            maxLines = 1
                        )
                    }
                    if (onRetry != null) {
                        TextButton(
                            onClick = onRetry,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(text = "重试", color = PrimaryBlue, maxLines = 1)
                        }
                    }
                    if (busy) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                Text(
                    text = "本地＝以本地内容覆盖/上传云端；云端＝移走本地文件后重新下载云端；删除＝两端都删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 批量删除待确认提示：本地一次移走/删除了过多文件时，先不删除云端，
 * 由用户确认后再同步删除，避免误删扩散；选择保留则把文件重新下载回本地。
 */
@Composable
fun PendingBulkDeleteCard(
    count: Int,
    busy: Boolean,
    onConfirm: () -> Unit,
    onKeep: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WarningOrange.copy(alpha = 0.12f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = WarningOrange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "待确认的批量删除（$count）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "本机有 $count 个文件被移走或删除。超过 50 个的批量删除需确认后才会同步到云端；" +
                    "确认后删除云端文件，选择保留则不删除云端，并会重新下载回本地。",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onKeep, enabled = !busy) {
                    Text(
                        text = "保留云端",
                        color = OnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                TextButton(onClick = onConfirm, enabled = !busy) {
                    Text(
                        text = if (busy) "处理中…" else "确认删除",
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
