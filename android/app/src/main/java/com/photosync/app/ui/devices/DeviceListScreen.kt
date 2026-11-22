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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.repository.DEFAULT_DEVICE_NAME
import com.photosync.app.ui.components.ConfirmBottomSheet
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.ErrorBanner
import com.photosync.app.ui.components.GradientButton
import com.photosync.app.ui.components.IconBadge
import com.photosync.app.ui.components.OnlineStatusRow
import com.photosync.app.ui.theme.AccentCyan
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.util.formatIsoTime

/** 设备管理页（首页底部导航"设备" Tab） */
@Composable
fun DeviceListScreen() {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: DeviceListViewModel = viewModel {
        DeviceListViewModel(app.container.deviceRepository)
    }
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    var renameTarget by remember { mutableStateOf<LocalDevice?>(null) }
    var removeTarget by remember { mutableStateOf<LocalDevice?>(null) }

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
                            subtitle = "点击下方「添加新设备」注册本机"
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
                        items(devices, key = { it.id }) { device ->
                            DeviceCard(
                                device = device,
                                onRename = { renameTarget = device },
                                onRemove = { removeTarget = device }
                            )
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            GradientButton(
                                text = "添加新设备",
                                onClick = {
                                    val thisName = devices.firstOrNull { it.isThisDevice }?.deviceName
                                        ?: DEFAULT_DEVICE_NAME
                                    viewModel.addThisDevice(thisName)
                                },
                                leadingIcon = Icons.Filled.Add
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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
        ConfirmBottomSheet(
            title = "移除设备",
            message = "确定移除「${device.deviceName}」？移除后该设备需重新注册才能同步，但不会删除它已同步的服务器文件。",
            confirmText = "移除设备",
            onConfirm = { viewModel.remove(device.id) },
            onDismiss = { removeTarget = null }
        )
    }
}

@Composable
private fun DeviceCard(
    device: LocalDevice,
    onRename: () -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
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
                OnlineStatusRow(
                    online = device.online,
                    lastSeenText = "最近在线：${formatIsoTime(device.lastSeenAt)}"
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "平台：${device.platform} · 凭据：${device.credentialStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("移除设备") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        enabled = !device.isThisDevice,
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
