package com.photosync.app.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.ui.components.AppSnackbarHost
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.ErrorBanner
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.components.SecondaryButton
import com.photosync.app.ui.components.IconBadge
import com.photosync.app.ui.components.appTextFieldColors
import com.photosync.app.ui.theme.AccentCyan
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.SuccessGreen
import com.photosync.app.ui.theme.WarningOrange
import com.photosync.app.util.formatIsoTime

/** 设备管理页（首页底部导航"设备" Tab） */
@Composable
fun DeviceListScreen() {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: DeviceListViewModel = viewModel {
        DeviceListViewModel(app.container.deviceRepository, app.container.deviceName)
    }
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actionInProgress by viewModel.actionInProgress.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    var renameTarget by remember { mutableStateOf<LocalDevice?>(null) }
    var removeTarget by remember { mutableStateOf<LocalDevice?>(null) }

    val primaryDevice = devices.firstOrNull { it.isPrimary }
    val thisDevice = devices.firstOrNull { it.isThisDevice }
    val thisDeviceRemoved = thisDevice?.credentialStatus == "revoked"

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "设备管理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新设备列表")
                }
            }

            when {
                uiState is DeviceListUiState.Loading && devices.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState is DeviceListUiState.Error && devices.isEmpty() -> {
                    Column(modifier = Modifier.weight(1f)) {
                        Spacer(Modifier.height(8.dp))
                        ErrorBanner(
                            message = (uiState as DeviceListUiState.Error).message,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        TextButton(
                            onClick = { viewModel.refresh() },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("重新加载", color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                devices.isEmpty() -> {
                    Column(modifier = Modifier.weight(1f)) {
                        EmptyStateView(
                            icon = Icons.Filled.DeviceUnknown,
                            title = "还没有已连接的设备",
                            subtitle = "登录同一账号的设备会自动显示在这里"
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (thisDeviceRemoved) {
                            item {
                                RemovedThisDeviceCard(
                                    requested = thisDevice?.isRejoinRequested == true,
                                    busy = actionInProgress,
                                    onRequestRejoin = viewModel::requestRejoin
                                )
                            }
                        } else {
                            item {
                                PrimaryDeviceCard(
                                    primaryDevice = primaryDevice,
                                    thisDevice = thisDevice,
                                    busy = actionInProgress,
                                    onClaim = viewModel::claimPrimary,
                                    onRelease = viewModel::releasePrimary
                                )
                            }
                        }
                        items(devices, key = { it.id }) { device ->
                            DeviceCard(
                                device = device,
                                viewerIsPrimary = primaryDevice?.isThisDevice == true,
                                busy = actionInProgress,
                                onRename = { renameTarget = device },
                                onRemove = { removeTarget = device },
                                onApproveRejoin = { viewModel.approveRejoin(device.id) },
                                onRejectRejoin = { viewModel.rejectRejoin(device.id) }
                            )
                        }
                    }
                }
            }
        }

        AppSnackbarHost(hostState = snackbarHostState)
    }

    renameTarget?.let { device ->
        RenameDeviceDialog(
            device = device,
            onConfirm = { newName ->
                viewModel.rename(device.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    removeTarget?.let { device ->
        RemoveDeviceDialog(
            device = device,
            onConfirm = {
                viewModel.removeDevice(device.id)
                removeTarget = null
            },
            onDismiss = { removeTarget = null }
        )
    }
}

/** 主从设备管理：设为主设备 / 放弃主设备身份。移除从设备的入口在各从设备菜单中。 */
@Composable
private fun PrimaryDeviceCard(
    primaryDevice: LocalDevice?,
    thisDevice: LocalDevice?,
    busy: Boolean,
    onClaim: () -> Unit,
    onRelease: () -> Unit
) {
    val thisIsPrimary = thisDevice?.isPrimary == true
    val canClaim = primaryDevice == null && thisDevice != null && !busy

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "主从设备管理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    primaryDevice == null -> "当前没有主设备，任意设备都可以抢占主设备身份。"
                    primaryDevice.isThisDevice -> "当前主设备：本机。可在其它从设备的菜单中移除它们。"
                    else -> "当前主设备：${primaryDevice.deviceName}。只有主设备可以移除从设备。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = if (thisIsPrimary) "放弃主设备身份" else "设为主设备",
                onClick = if (thisIsPrimary) onRelease else onClaim,
                modifier = Modifier.fillMaxWidth(),
                enabled = if (thisIsPrimary) !busy else canClaim
            )
        }
    }
}

/** 本机已被主设备移除：可发起重新加入申请，等主设备同意后才能恢复。 */
@Composable
private fun RemovedThisDeviceCard(
    requested: Boolean,
    busy: Boolean,
    onRequestRejoin: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "本机已被移除",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ErrorRed
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (requested) {
                    "已提交重新加入申请，等待主设备同意。"
                } else {
                    "当前设备已被主设备移出账号，申请后需要主设备同意才能重新加入。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = if (requested) "已申请，等待主设备同意" else "申请重新加入",
                onClick = onRequestRejoin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !requested && !busy
            )
        }
    }
}

/** 移除从设备确认：入口在各从设备卡片的更多操作菜单里。 */
@Composable
private fun RemoveDeviceDialog(
    device: LocalDevice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移除从设备") },
        text = {
            Text(
                "确定移除「${device.deviceName}」吗？移除后该设备会显示为被移除，需要主设备同意才能重新加入。",
                color = OnSurfaceMuted
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("移除", fontWeight = FontWeight.Bold, color = ErrorRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = OnSurfaceMuted)
            }
        }
    )
}

/** 被移除设备的状态行：显示“被移除”，有待处理申请时附带提示。 */
@Composable
private fun RemovedStatusRow(requested: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.DeviceUnknown,
            contentDescription = null,
            tint = ErrorRed,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "被移除",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = ErrorRed
        )
        if (requested) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "已申请重新加入，等待主设备同意",
                style = MaterialTheme.typography.bodySmall,
                color = WarningOrange
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: LocalDevice,
    viewerIsPrimary: Boolean,
    busy: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onApproveRejoin: () -> Unit,
    onRejectRejoin: () -> Unit
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val removed = device.credentialStatus == "revoked"
    // 只有主设备可以移除其它从设备；不能移除自己或主设备
    val canRemove = viewerIsPrimary && !removed && !device.isThisDevice && !device.isPrimary

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(
                icon = Icons.Filled.Smartphone,
                size = 46,
                background = if (device.isThisDevice) {
                    Brush.horizontalGradient(listOf(PrimaryBlue, AccentCyan))
                } else {
                    Brush.horizontalGradient(listOf(OnSurfaceMuted, OnSurfaceMuted))
                }
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (device.isThisDevice) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "本机",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PrimaryBlue.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (removed) {
                    RemovedStatusRow(requested = device.isRejoinRequested)
                    if (viewerIsPrimary && device.isRejoinRequested) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton(
                                text = "拒绝",
                                onClick = onRejectRejoin,
                                modifier = Modifier.weight(1f),
                                enabled = !busy
                            )
                            PrimaryButton(
                                text = "同意重新加入",
                                onClick = onApproveRejoin,
                                modifier = Modifier.weight(1f),
                                enabled = !busy
                            )
                        }
                    }
                } else {
                    // 主/从、在线状态、平台类型同一行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val roleColor = if (device.isPrimary) WarningOrange else OnSurfaceMuted
                        Text(
                            text = if (device.isPrimary) "主设备" else "从设备",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = roleColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(roleColor.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            imageVector = if (device.online) Icons.Filled.CheckCircle else Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = if (device.online) SuccessGreen else OnSurfaceMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (device.online) "在线" else "离线",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (device.online) SuccessGreen else OnSurfaceMuted
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "平台：${if (device.platform == "android") "Android" else device.platform}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    // 最近在线时间单独一行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = OnSurfaceMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "最近在线：${formatIsoTime(device.lastSeenAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted
                        )
                    }
                }
            }

            if (!removed) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        // 重命名只能改本机，主设备也不能改别人的设备
                        if (device.isThisDevice) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                }
                            )
                        }
                        if (canRemove) {
                            DropdownMenuItem(
                                text = { Text("移除设备", color = ErrorRed) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = ErrorRed
                                    )
                                },
                                enabled = !busy,
                                onClick = {
                                    menuExpanded = false
                                    onRemove()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDeviceDialog(
    device: LocalDevice,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(device.deviceName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名设备") },
        text = {
            Column {
                Text("为设备设置一个易识别的名称", color = OnSurfaceMuted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("设备名称") },
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty()
            ) {
                Text("保存", fontWeight = FontWeight.Bold, color = PrimaryBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = OnSurfaceMuted)
            }
        }
    )
}
