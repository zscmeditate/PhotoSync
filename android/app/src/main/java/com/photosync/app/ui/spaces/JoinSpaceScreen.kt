package com.photosync.app.ui.spaces

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.ErrorBanner
import com.photosync.app.ui.components.IconBadge
import com.photosync.app.ui.theme.OnSurfaceMuted

/** 加入空间：从同账号下已存在的空间中选择一个，再进入本地目录绑定。 */
@Composable
fun JoinSpaceScreen(
    onBack: () -> Unit,
    onSelectSpace: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: JoinSpaceViewModel = viewModel {
        JoinSpaceViewModel(app.container.spaceRepository)
    }
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SpacePageHeader(
            title = "加入同步空间",
            onBack = onBack,
            actions = {
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新可加入的空间")
                }
            }
        )

        when {
            refreshing && spaces.isEmpty() -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            spaces.isEmpty() -> {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    loadError?.let {
                        ErrorBanner(message = it)
                        Spacer(Modifier.height(12.dp))
                    }
                    EmptyStateView(
                        icon = Icons.Filled.Folder,
                        title = "暂无可加入的空间",
                        subtitle = "同一账号下其他设备创建的空间会显示在这里；已绑定目录或正在等待审批的空间不会重复出现"
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "选择一个已有空间，下一步再选择本机的同步目录。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceMuted
                        )
                    }
                    loadError?.let { error ->
                        item { ErrorBanner(message = error) }
                    }
                    items(spaces, key = { it.id }) { space ->
                        JoinSpaceCard(space = space, onClick = { onSelectSpace(space.id) })
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun JoinSpaceCard(space: LocalSpace, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(icon = Icons.Filled.Folder, size = 46)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = space.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                space.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    text = "${space.memberCount} 台设备 · ${space.mappingCount} 台已绑定目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = OnSurfaceMuted
            )
        }
    }
}
