package com.photosync.app.ui.spaces

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.repository.ScanPreview
import com.photosync.app.ui.components.ErrorBanner
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.components.SecondaryButton
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.SuccessGreen
import com.photosync.app.ui.theme.WarningOrange

@Composable
fun AddMappingScreen(
    spaceId: String,
    onBack: () -> Unit,
    onFinished: () -> Unit = onBack
) {
    val context = LocalContext.current
    val app = context.applicationContext as PhotoSyncApp
    val viewModel: AddMappingViewModel = viewModel(key = spaceId) {
        AddMappingViewModel(app.container.spaceRepository, spaceId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val label = DocumentFile.fromTreeUri(context, uri)?.name
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "已选目录"
                viewModel.selectDirectory(uri.toString(), label)
            } catch (e: SecurityException) {
                viewModel.showError("目录授权失败：系统未授予持久读写权限，请重新选择目录")
            } catch (e: Exception) {
                viewModel.showError("目录授权失败：${e.message ?: "无法读取所选目录"}")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SpacePageHeader(title = "绑定本地目录", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            state.error?.let {
                ErrorBanner(message = it)
                Spacer(Modifier.height(16.dp))
            }

            when {
                state.joinSubmitted -> {
                    JoinPendingContent(onDone = onFinished)
                }
                state.preview != null -> {
                    ScanPreviewContent(preview = state.preview!!, onDone = onFinished)
                }
                state.scanning -> {
                    Spacer(Modifier.height(48.dp))
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "正在扫描本地目录",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Text(
                        text = "已扫描 ${state.scannedCount} 个文件",
                        color = OnSurfaceMuted,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                    )
                    Spacer(Modifier.height(20.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.currentFileName.ifEmpty { "正在准备…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(20.dp))
                    TextButton(
                        onClick = viewModel::cancelScan,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("取消扫描")
                    }
                }
                else -> {
                    MappingForm(
                        state = state,
                        onSelectDirectory = { directoryLauncher.launch(null) },
                        onOnlyMediaChange = viewModel::updateOnlyMedia,
                        onConfirm = viewModel::confirm,
                        onDone = onFinished
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun MappingForm(
    state: AddMappingUiState,
    onSelectDirectory: () -> Unit,
    onOnlyMediaChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDone: () -> Unit
) {
    val alreadyCreated = state.createdMappingId != null
    Text("① 选择本地目录", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "该目录会作为空间根目录，子文件夹和文件结构会完整同步到服务端。",
        style = MaterialTheme.typography.bodySmall,
        color = OnSurfaceMuted,
        modifier = Modifier.padding(top = 6.dp)
    )
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = onSelectDirectory,
        enabled = !state.creating && !alreadyCreated,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(if (state.treeUri == null) "通过系统文件选择器选择" else "重新选择目录")
    }
    if (state.treeUri != null) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(state.localPathLabel, fontWeight = FontWeight.SemiBold)
                Text(
                    text = state.treeUri,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "已获得持久读写授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = SuccessGreen
                )
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("② 同步选项", style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("仅同步照片和视频")
            Text("按 MIME 类型或扩展名过滤", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        }
        Switch(
            checked = state.onlyMedia,
            onCheckedChange = onOnlyMediaChange,
            enabled = !state.creating && !alreadyCreated
        )
    }

    Spacer(Modifier.height(24.dp))
    Text("③ 确认并建立索引", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    PrimaryButton(
        text = if (state.creating) "正在绑定目录…" else "确认绑定并扫描",
        onClick = onConfirm,
        enabled = !state.creating && !alreadyCreated && state.treeUri != null,
        leadingIcon = Icons.Filled.Add
    )
    if (alreadyCreated) {
        Spacer(Modifier.height(12.dp))
        SecondaryButton(text = "返回空间详情", onClick = onDone)
    }
}

@Composable
private fun ColumnScope.JoinPendingContent(onDone: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Icon(
        imageVector = Icons.Filled.HourglassEmpty,
        contentDescription = null,
        tint = PrimaryBlue,
        modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally)
    )
    Text(
        text = "已提交加入申请",
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)
    )
    Text(
        text = "主设备同意后将自动用刚才选择的目录完成绑定并开始同步。",
        style = MaterialTheme.typography.bodyMedium,
        color = OnSurfaceMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    Spacer(Modifier.height(24.dp))
    PrimaryButton(text = "返回空间详情", onClick = onDone, leadingIcon = Icons.Filled.CheckCircle)
}

@Composable
private fun ColumnScope.ScanPreviewContent(preview: ScanPreview, onDone: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = SuccessGreen,
        modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally)
    )
    Text(
        text = "首次同步预览",
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)
    )
    Text(
        text = "本地 ${preview.totalLocalCount} 项 · 远端 ${preview.summary.remoteCount} 项",
        color = OnSurfaceMuted,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)
    )
    Spacer(Modifier.height(24.dp))
    PreviewCountRow("将上传", preview.summary.toUpload, Icons.Filled.CloudUpload, PrimaryBlue)
    Spacer(Modifier.height(12.dp))
    PreviewCountRow("将下载", preview.summary.toDownload, Icons.Filled.CloudDownload, PrimaryBlue)
    Spacer(Modifier.height(12.dp))
    PreviewCountRow("冲突", preview.summary.conflictCount, Icons.Filled.SyncProblem, WarningOrange)
    if (preview.truncated) {
        Spacer(Modifier.height(16.dp))
        ErrorBanner(message = "清单过长已截断，仅上报前 10000 条；服务端不会据此判定完整差异")
    } else if (!preview.summary.diffComplete) {
        Spacer(Modifier.height(16.dp))
        Text("服务端返回的差异尚不完整，请稍后重新扫描。", color = ErrorRed)
    }
    Spacer(Modifier.height(20.dp))
    Text(
        text = "实际上传下载将在 Phase 3 开放。本阶段仅保存目录映射、本地文件索引和差异预览。",
        style = MaterialTheme.typography.bodyMedium,
        color = OnSurfaceMuted
    )
    Spacer(Modifier.height(24.dp))
    PrimaryButton(text = "完成", onClick = onDone, leadingIcon = Icons.Filled.CheckCircle)
}

@Composable
private fun PreviewCountRow(label: String, count: Int, icon: ImageVector, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("$count 项", style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
        }
    }
}
