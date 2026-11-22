package com.photosync.app.ui.spaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.ui.components.ErrorBanner
import com.photosync.app.ui.components.GradientButton
import com.photosync.app.ui.theme.OnSurfaceMuted

@Composable
fun SpaceEditScreen(
    spaceId: String?,
    onBack: () -> Unit,
    onSaved: (spaceId: String, created: Boolean) -> Unit
) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: SpaceEditViewModel = viewModel(key = spaceId ?: "create") {
        SpaceEditViewModel(app.container.spaceRepository, spaceId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.saved.collect { onSaved(it.spaceId, it.created) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SpacePageHeader(title = if (spaceId == null) "创建同步空间" else "编辑同步空间", onBack = onBack)
        if (state.loading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Text(
                    text = "空间是服务器统一管理的文件命名空间，可关联多台设备和多个本地目录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    label = { Text("空间名称") },
                    placeholder = { Text("例如：家庭照片") },
                    singleLine = true,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.description,
                    onValueChange = viewModel::updateDescription,
                    label = { Text("说明（可选）") },
                    placeholder = { Text("这个空间用于同步什么？") },
                    minLines = 3,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.deviceId.ifEmpty { "未注册" },
                    onValueChange = {},
                    label = { Text("初始设备 ID（默认本机）") },
                    enabled = false,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                state.error?.let {
                    ErrorBanner(message = it)
                    Spacer(Modifier.height(16.dp))
                }
                GradientButton(
                    text = if (state.saving) "正在保存…" else if (spaceId == null) "创建空间" else "保存修改",
                    onClick = viewModel::save,
                    enabled = !state.saving,
                    leadingIcon = if (spaceId == null) Icons.Filled.Add else Icons.Filled.Check
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (spaceId == null) "创建后将进入空间详情，可立即添加同步目录。" else "空间名称与说明会保存到服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
