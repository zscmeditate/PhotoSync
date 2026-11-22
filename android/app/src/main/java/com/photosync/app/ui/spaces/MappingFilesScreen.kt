package com.photosync.app.ui.spaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.util.formatIsoTime

@Composable
fun MappingFilesScreen(
    mappingId: String,
    onBack: () -> Unit,
    onOpenVersions: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: MappingFilesViewModel = viewModel(key = mappingId) {
        MappingFilesViewModel(app.container.spaceRepository, mappingId)
    }
    val mapping by viewModel.mapping.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        SpacePageHeader(title = mapping?.localPathLabel ?: "映射文件", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "远端：/${mapping?.remotePrefix.orEmpty()}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            mapping?.let { MappingStatusChip(it.status) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "扫描时间：${formatIsoTime(mapping?.lastScannedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted
                        )
                        Text(
                            text = "scanComplete：${if (mapping?.lastScanComplete == true) "true（完整）" else "false（未完成）"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mapping?.lastScanComplete == true) PrimaryBlue else OnSurfaceMuted
                        )
                    }
                }
            }
            if (files.isEmpty()) {
                item {
                    EmptyStateView(
                        icon = Icons.Filled.Description,
                        title = "暂无本地文件索引",
                        subtitle = "返回空间详情执行扫描；扫描失败不会清空已有索引"
                    )
                }
            } else {
                items(files, key = { it.id }) { file ->
                    FileIndexRow(
                        file = file,
                        onClick = file.remoteFileId?.let { { onOpenVersions(it) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileIndexRow(file: LocalFileIndex, onClick: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = PrimaryBlue)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                FileStatusChip(file.status)
            }
            Text(
                text = file.relativePath,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 5.dp)
            )
            Text(
                text = "${formatFileSize(file.size)} · ${formatModifiedAt(file.modifiedAt)} · ${if (file.sha256 == null) "未校验" else "SHA-256 已校验"}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}
