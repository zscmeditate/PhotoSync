package com.photosync.app.ui.tasks

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.photosync.app.data.local.SyncTaskState
import com.photosync.app.data.local.SyncTaskType
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.SuccessGreen
import com.photosync.app.ui.theme.WarningOrange
import java.text.DateFormat
import java.util.Date

/** 任务/活动页共用的类型与状态展示。 */
object TaskPresentation {

    fun typeLabel(type: String): String = when (type) {
        SyncTaskType.UPLOAD -> "上传"
        SyncTaskType.DOWNLOAD -> "下载"
        SyncTaskType.DELETE -> "删除"
        SyncTaskType.RENAME -> "重命名"
        else -> type
    }

    fun typeIcon(type: String): ImageVector = when (type) {
        SyncTaskType.UPLOAD -> Icons.Filled.CloudUpload
        SyncTaskType.DOWNLOAD -> Icons.Filled.CloudDownload
        SyncTaskType.DELETE -> Icons.Filled.Delete
        SyncTaskType.RENAME -> Icons.Filled.DriveFileRenameOutline
        else -> Icons.Filled.Sync
    }

    fun stateLabel(state: String): String = when (state) {
        SyncTaskState.PENDING -> "等待执行"
        SyncTaskState.RUNNING -> "进行中"
        SyncTaskState.RETRY -> "等待重试"
        SyncTaskState.FAILED -> "失败"
        SyncTaskState.COMPLETED -> "已完成"
        else -> state
    }

    fun stateIcon(state: String): ImageVector = when (state) {
        SyncTaskState.PENDING, SyncTaskState.RETRY -> Icons.Filled.Schedule
        SyncTaskState.RUNNING -> Icons.Filled.Sync
        SyncTaskState.FAILED -> Icons.Filled.SyncProblem
        SyncTaskState.COMPLETED -> Icons.Filled.CheckCircle
        else -> Icons.Filled.ErrorOutline
    }

    fun stateColor(state: String): Color = when (state) {
        SyncTaskState.PENDING, SyncTaskState.RETRY -> WarningOrange
        SyncTaskState.RUNNING -> PrimaryBlue
        SyncTaskState.FAILED -> ErrorRed
        SyncTaskState.COMPLETED -> SuccessGreen
        else -> OnSurfaceMuted
    }

    /** 任务中心可重试：失败、等待重试与等待执行的任务。 */
    fun canRetry(state: String): Boolean = state != SyncTaskState.RUNNING &&
        state != SyncTaskState.COMPLETED

    /** 任务中心可取消：尚未执行的任务；运行中的任务由 Worker 自行结束。 */
    fun canCancel(state: String): Boolean = state == SyncTaskState.PENDING ||
        state == SyncTaskState.RETRY ||
        state == SyncTaskState.FAILED

    fun formatTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

    fun formatDate(millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    fun progressText(bytes: Long, size: Long): String {
        if (size <= 0L) return ""
        val percent = (bytes * 100 / size).coerceIn(0L, 100L)
        return "$percent%"
    }

    fun displayName(path: String): String = path.substringAfterLast('/').ifBlank { path }

    fun errorText(error: String?): String? {
        if (error.isNullOrBlank()) return null
        val code = error.substringBefore('：')
        val message = error.substringAfter('：', "")
        return com.photosync.app.ui.util.ErrorMessages.forCode(code, message)
    }
}
