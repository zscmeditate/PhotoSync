package com.photosync.app.data.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.photosync.app.di.AppContainer
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出本机诊断日志：设备/服务端信息、同步设置与本机最近同步任务。
 * 不包含 Token、密码等敏感凭据，文件写入 cache/diagnostics 后通过 FileProvider 分享。
 */
object DiagnosticsExporter {

    suspend fun export(context: Context, container: AppContainer): File {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "photosync-diagnostics-$stamp.txt")
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val serverUrl = container.settingsRepository.getServerBaseUrl().ifBlank { "未配置" }
        val preferences = container.settingsRepository.getSyncPreferences()
        val deviceId = container.syncRepository.currentDeviceId() ?: "未注册"
        val tasks = container.syncRepository.observeRecentTasks(200).first()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        file.bufferedWriter().use { writer ->
            writer.appendLine("PhotoSync 诊断日志")
            writer.appendLine("导出时间：$time")
            writer.appendLine("App 版本：$versionName")
            writer.appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            writer.appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            writer.appendLine("服务器：$serverUrl")
            writer.appendLine("本机设备 ID：$deviceId")
            writer.appendLine(
                "同步设置：自动同步=${preferences.autoSyncEnabled}，" +
                    "仅 Wi-Fi=${preferences.wifiOnly}，间隔=${preferences.intervalMinutes} 分钟"
            )
            writer.appendLine()
            writer.appendLine("最近同步任务（${tasks.size} 条）：")
            tasks.forEach { task ->
                writer.appendLine(
                    listOf(
                        formatTime(task.updatedAt),
                        task.state,
                        task.type,
                        task.remoteRelativePath,
                        "attempt=${task.attempt}",
                        "bytes=${task.bytesTransferred}/${task.size}",
                        task.error ?: ""
                    ).joinToString(" | ")
                )
            }
        }
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PhotoSync 诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}
