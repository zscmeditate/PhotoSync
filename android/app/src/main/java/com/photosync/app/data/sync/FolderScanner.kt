package com.photosync.app.data.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.photosync.app.data.local.FileSyncStatus
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.local.LocalFolderMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class FolderAccessException(message: String) : Exception(message)
class FolderScanException(message: String) : Exception(message)

data class FolderScanProgress(
    val scannedCount: Int,
    val currentFileName: String
)

/** 递归读取 SAF DocumentFile，并以流式 SHA-256 建立本地索引。 */
class FolderScanner(private val context: Context) {

    suspend fun scan(
        mapping: LocalFolderMapping,
        onProgress: (FolderScanProgress) -> Unit = {}
    ): List<LocalFileIndex> = withContext(Dispatchers.IO) {
        if (mapping.treeUri.isBlank()) {
            throw FolderAccessException("本地目录授权不存在，请重新选择目录")
        }
        val root = try {
            DocumentFile.fromTreeUri(context, Uri.parse(mapping.treeUri))
        } catch (e: SecurityException) {
            throw FolderAccessException("本地目录授权已失效，请重新授权")
        } catch (e: Exception) {
            throw FolderScanException(e.message ?: "无法打开本地目录")
        }
        if (root == null || !root.exists() || !root.isDirectory || !root.canRead()) {
            throw FolderAccessException("本地目录不存在或不可读，请重新授权")
        }

        val files = mutableListOf<LocalFileIndex>()
        val scannedAt = System.currentTimeMillis()

        suspend fun visit(directory: DocumentFile, parentPath: String) {
            coroutineContext.ensureActive()
            val children = try {
                directory.listFiles()
            } catch (e: SecurityException) {
                throw FolderAccessException("读取目录时授权失效，请重新授权")
            } catch (e: Exception) {
                throw FolderScanException(e.message ?: "读取目录失败")
            }

            for (document in children) {
                coroutineContext.ensureActive()
                val name = document.name
                    ?: throw FolderScanException("目录中存在无法识别名称的项目")
                if (MediaFileFilter.isTemporarySyncFile(name)) continue
                // 系统回收站条目（系统已删除的文件）不参与同步。
                // MIUI 的 DocumentFile.name 返回展示名（不含 .trashed- 前缀），
                // 必须检查 URI 里的 SAF 文档 ID，形如 "primary:Download/.trashed-<ts>-<realname>"。
                // 注意 lastPathSegment 不是纯文件名，前缀检查会失效，需按路径段判断。
                val documentId = runCatching {
                    DocumentsContract.getDocumentId(document.uri)
                }.getOrNull()
                if (MediaFileFilter.isSystemTrashPath(name) ||
                    MediaFileFilter.isSystemTrashPath(document.uri.lastPathSegment.orEmpty()) ||
                    (documentId != null && MediaFileFilter.isSystemTrashPath(documentId))
                ) continue
                if (document.isDirectory) {
                    // 本地恢复区是应用内部目录，不能被当成同步内容上传。
                    if (name == LOCAL_TRASH_DIR_NAME) continue
                    // 系统回收站目录（.Trash / .trash）整目录跳过。
                    if (MediaFileFilter.isSystemTrashDir(name)) continue
                    val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    visit(document, path)
                    continue
                }
                if (!document.exists() || !document.canRead()) {
                    throw FolderAccessException("文件「$name」不可读，目录授权可能已失效")
                }
                if (mapping.onlyMedia && !MediaFileFilter.isMedia(document.type, name)) continue

                val relativePath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                onProgress(FolderScanProgress(files.size, name))
                val size = document.length().coerceAtLeast(0L)
                val sha256 = if (size > MAX_HASH_BYTES) null else sha256(document)
                files += LocalFileIndex(
                    mappingId = mapping.id,
                    relativePath = relativePath,
                    fileName = name,
                    size = size,
                    modifiedAt = document.lastModified(),
                    sha256 = sha256,
                    docUri = document.uri.toString(),
                    status = FileSyncStatus.PENDING_UPLOAD,
                    lastScannedAt = scannedAt
                )
                onProgress(FolderScanProgress(files.size, name))
            }
        }

        visit(root, "")
        files
    }

    private suspend fun sha256(document: DocumentFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = try {
            context.contentResolver.openInputStream(document.uri)
        } catch (e: SecurityException) {
            throw FolderAccessException("读取文件时授权失效，请重新授权")
        } catch (e: Exception) {
            throw FolderScanException(e.message ?: "无法读取文件「${document.name.orEmpty()}」")
        } ?: throw FolderScanException("无法打开文件「${document.name.orEmpty()}」")

        input.use { stream ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = try {
                    stream.read(buffer)
                } catch (e: SecurityException) {
                    throw FolderAccessException("读取文件时授权失效，请重新授权")
                } catch (e: Exception) {
                    throw FolderScanException(e.message ?: "计算文件校验值失败")
                }
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_HASH_BYTES = 200L * 1024L * 1024L
        const val HASH_BUFFER_SIZE = 64 * 1024
    }
}
