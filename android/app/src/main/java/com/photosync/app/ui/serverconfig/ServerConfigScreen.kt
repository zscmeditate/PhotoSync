package com.photosync.app.ui.serverconfig

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Http
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.ui.components.AppTextField
import com.photosync.app.ui.components.ErrorBanner
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.components.SecondaryButton
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.SuccessGreen

/**
 * 服务器配置页：输入基础 URL、测试连接（/api/v1/health）、保存并继续。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigScreen(
    onBack: () -> Unit,
    onSaved: (loggedIn: Boolean) -> Unit
) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: ServerConfigViewModel = viewModel {
        ServerConfigViewModel(app.container.serverRepository, app.container.secureTokenStore)
    }
    val health by viewModel.health.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var url by rememberSaveable { mutableStateOf("") }
    var allowHttp by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (url.isBlank()) {
            url = app.container.serverRepository.getServerBaseUrl()
        }
    }

    LaunchedEffect(state) {
        if (state is ServerConfigState.Saved) {
            onSaved(viewModel.isLoggedIn())
            viewModel.consumeState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("服务器配置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = "服务器地址",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "填写自建 Node.js 服务的 HTTPS 基础地址，例如 https://sync.example.com",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted
            )
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = url,
                onValueChange = {
                    // 输入过程中不做裁剪，否则末尾的 "/" 会被立刻删除导致无法输入
                    url = it
                },
                label = "服务器地址",
                placeholder = "https://sync.example.com",
                leadingIcon = Icons.Filled.Dns,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = allowHttp,
                    onCheckedChange = { allowHttp = it }
                )
                Icon(
                    imageVector = Icons.Filled.Http,
                    contentDescription = null,
                    tint = OnSurfaceMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "允许 HTTP（仅开发环境）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted
                )
            }

            Spacer(Modifier.height(16.dp))
            SecondaryButton(text = "测试连接", onClick = {
                viewModel.testConnection(url, allowHttp)
            }, enabled = !health.testing)

            Spacer(Modifier.height(20.dp))
            HealthResultCard(health)

            val errorState = state as? ServerConfigState.Error
            if (errorState != null) {
                Spacer(Modifier.height(12.dp))
                ErrorBanner(message = errorState.message)
            }

            Spacer(Modifier.height(28.dp))
            PrimaryButton(
                text = "保存并继续",
                onClick = { viewModel.save(url, allowHttp) },
                enabled = !health.testing
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HealthResultCard(health: HealthUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                health.testing -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                health.connected -> {
                    Icon(
                        imageVector = Icons.Filled.CloudDone,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = OnSurfaceMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "连接状态：${health.statusText}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        InfoRow("API 版本", health.apiVersion)
        InfoRow("服务端时间", health.serverTime)
        InfoRow("延迟", health.latencyText)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}
