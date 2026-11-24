package com.photosync.app.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.local.LocalFolderMapping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class SafFileException(message: String, cause: Throwable? = null) : IOException(message, cause)
class ContentVerificationException(message: String) : Exception(message)

data class LocalContentDigest(
    val size: Long,
    val sha256: String
)

/** SAF 内容读取、分块请求体，以及下载临时文件的原子提交。 */
class SafFileStore(private val context: Context) {

    suspend fun openIndexedFile(index: LocalFileIndex): DocumentFile = withContext(Dispatchers.IO) {
        if (index.docUri.isBlank()) throw SafFileException("本地文件索引缺少内容 URI")
        val document = try {
            DocumentFile.fromSingleUri(context, Uri.parse(index.docUri))
        } catch (e: SecurityException) {
            throw FolderAccessException("本地文件授权已失效，请重新授权目录")
        } catch (e: Exception) {
            throw SafFileException("无法打开本地文件", e)
        }
        if (document == null || !document.exists() || !document.isFile || !document.canRead()) {
            throw SafFileException("本地文件不存在或不可读：${index.relativePath}")
        }
        document
    }

    suspend fun findTarget(
        mapping: LocalFolderMapping,
        localRelativePath: String
    ): DocumentFile? = withContext(Dispatchers.IO) {
        val segments = validatedSegments(localRelativePath)
        var current = resolveRoot(mapping)
        for ((index, segment) in segments.withIndex()) {
            val child = try {
                current.findFile(segment)
            } catch (e: SecurityException) {
                throw FolderAccessException("读取本地目录时授权失效")
            } catch (e: Exception) {
                throw SafFileException("无法查找本地文件：$localRelativePath", e)
            } ?: return@withContext null
            if (index < segments.lastIndex && !child.isDirectory) {
                throw SafFileException("本地路径的父级不是目录：$localRelativePath")
            }
            current = child
        }
        current
    }

    suspend fun delete(mapping: LocalFolderMapping, localRelativePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val target = findTarget(mapping, localRelativePath) ?: return@withContext false
            if (!target.exists()) return@withContext false
            try {
                target.delete()
            } catch (e: SecurityException) {
                throw FolderAccessException("删除本地文件时授权失效")
            } catch (e: Exception) {
                throw SafFileException("删除本地文件失败：$localRelativePath", e)
            }
        }

    /**
     * 将本地文件从 oldLocalRelativePath 移动到 newLocalRelativePath。
     * 同目录内直接 renameTo；跨目录则复制内容后删除源文件（SAF 不支持跨目录 move）。
     */
    suspend fun move(
        mapping: LocalFolderMapping,
        oldLocalRelativePath: String,
        newLocalRelativePath: String
    ): DocumentFile = withContext(Dispatchers.IO) {
        val src = findTarget(mapping, oldLocalRelativePath)
            ?: throw SafFileException("待移动的源文件不存在：$oldLocalRelativePath")
        if (!src.isFile) throw SafFileException("源不是文件：$oldLocalRelativePath")
        val newSegments = validatedSegments(newLocalRelativePath)
        val newFileName = newSegments.last()
        val root = resolveRoot(mapping)
        val newParent = createParents(root, newSegments.dropLast(1))
        val oldParentPath = oldLocalRelativePath.substringBeforeLast('/', "")
        val newParentPath = newLocalRelativePath.substringBeforeLast('/', "")
        if (oldParentPath == newParentPath) {
            if (!src.renameTo(newFileName)) {
                throw SafFileException("重命名失败：$oldLocalRelativePath -> $newLocalRelativePath")
            }
        } else {
            if (newParent.findFile(newFileName) != null) {
                throw LocalPathConflictException("目标文件已存在，不能覆盖：$newLocalRelativePath")
            }
            copyStream(src, newParent, newFileName)
            if (!src.delete()) {
                throw SafFileException("移动后无法删除源文件：$oldLocalRelativePath")
            }
        }
        findTarget(mapping, newLocalRelativePath)
            ?: throw SafFileException("移动后无法读取目标文件：$newLocalRelativePath")
    }

    /**
     * 把文件移入应用管理的本地恢复区（而不是物理删除），供 Phase 5 的删除保护使用。
     * SAF 不支持跨目录 move，统一复制到 `.photosync-trash/<时间戳>/` 后删除源文件。
     */
    suspend fun moveToTrash(
        mapping: LocalFolderMapping,
        localRelativePath: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        val src = findTarget(mapping, localRelativePath) ?: return@withContext false
        if (!src.isFile) return@withContext false
        val root = resolveRoot(mapping)
        val trashRoot = try {
            root.findFile(LOCAL_TRASH_DIR_NAME) ?: root.createDirectory(LOCAL_TRASH_DIR_NAME)
        } catch (e: SecurityException) {
            throw FolderAccessException("创建本地恢复区时授权失效")
        } catch (e: Exception) {
            throw SafFileException("无法创建本地恢复区", e)
        } ?: throw SafFileException("无法创建本地恢复区")
        val batch = try {
            trashRoot.findFile(now.toString()) ?: trashRoot.createDirectory(now.toString())
        } catch (e: SecurityException) {
            throw FolderAccessException("创建恢复区批次目录时授权失效")
        } catch (e: Exception) {
            throw SafFileException("无法创建恢复区批次目录", e)
        } ?: throw SafFileException("无法创建恢复区批次目录")
        val flatName = localRelativePath.replace('/', '_')
        copyStream(src, batch, flatName)
        if (!src.delete()) {
            throw SafFileException("移入恢复区后无法删除源文件：$localRelativePath")
        }
        true
    }

    /**
     * 把本地文件复制为冲突副本（`name (conflict from <device>).ext`），名称冲突时追加序号。
     * 返回副本的相对路径；原文件保持不变。
     */
    suspend fun copyToConflictCopy(
        mapping: LocalFolderMapping,
        localRelativePath: String,
        deviceId: String
    ): String = withContext(Dispatchers.IO) {
        val src = findTarget(mapping, localRelativePath)
            ?: throw SafFileException("待复制文件不存在：$localRelativePath")
        if (!src.isFile) throw SafFileException("待复制路径不是文件：$localRelativePath")
        val segments = validatedSegments(localRelativePath)
        val fileName = segments.last()
        val parent = createParents(resolveRoot(mapping), segments.dropLast(1))
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val tag = "conflict from ${deviceId.take(8)}"
        var candidate = "$base ($tag)$ext"
        var suffix = 2
        while (parent.findFile(candidate) != null) {
            candidate = "$base ($tag $suffix)$ext"
            suffix += 1
        }
        copyStream(src, parent, candidate)
        val prefix = segments.dropLast(1)
        if (prefix.isEmpty()) candidate else prefix.joinToString("/") + "/" + candidate
    }

    suspend fun digest(document: DocumentFile): LocalContentDigest = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val input = openInput(document)
        input.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = try {
                    stream.read(buffer)
                } catch (e: SecurityException) {
                    throw FolderAccessException("读取本地文件时授权失效")
                } catch (e: IOException) {
                    throw SafFileException("读取本地文件失败：${document.name.orEmpty()}", e)
                }
                if (count < 0) break
                if (count > 0) {
                    digest.update(buffer, 0, count)
                    size += count
                }
            }
        }
        LocalContentDigest(size, digest.hex())
    }

    fun partRequestBody(document: DocumentFile, offset: Long, length: Long): RequestBody {
        return object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()

            override fun contentLength(): Long = length

            override fun writeTo(sink: BufferedSink) {
                openInput(document).use { input ->
                    skipFully(input, offset)
                    var remaining = length
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (remaining > 0L) {
                        val count = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw EOFException("本地文件在分块上传前已缩短")
                        if (count > 0) {
                            sink.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
            }
        }
    }

    suspend fun downloadVerified(
        mapping: LocalFolderMapping,
        localRelativePath: String,
        taskId: String,
        input: InputStream,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: suspend (Long) -> Unit = {}
    ): DocumentFile = withContext(Dispatchers.IO) {
        val segments = validatedSegments(localRelativePath)
        val fileName = segments.last()
        val root = resolveRoot(mapping)
        val parent = createParents(root, segments.dropLast(1))
        if (parent.findFile(fileName) != null) {
            throw LocalPathConflictException("目标文件已存在，不能覆盖：$localRelativePath")
        }

        val tempName = ".photosync-$taskId.tmp"
        parent.findFile(tempName)?.let { stale ->
            if (!stale.delete()) throw SafFileException("无法清理上次下载的临时文件")
        }
        val temp = try {
            parent.createFile("application/octet-stream", tempName)
        } catch (e: SecurityException) {
            throw FolderAccessException("创建下载文件时授权失效")
        } catch (e: Exception) {
            throw SafFileException("无法创建下载临时文件", e)
        } ?: throw SafFileException("无法创建下载临时文件")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val output = try {
                context.contentResolver.openOutputStream(temp.uri, "w")
            } catch (e: SecurityException) {
                throw FolderAccessException("写入下载文件时授权失效")
            } catch (e: Exception) {
                throw SafFileException("无法写入下载临时文件", e)
            } ?: throw SafFileException("无法打开下载临时文件输出流")

            output.use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var lastReported = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        stream.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        size += count
                        // 节流上报下载进度（每 256KB），供任务中心的进度条实时展示。
                        if (size - lastReported >= PROGRESS_REPORT_STEP) {
                            lastReported = size
                            onProgress(size)
                        }
                    }
                }
                stream.flush()
            }

            val actualSha256 = digest.hex()
            if (size != expectedSize) {
                throw ContentVerificationException(
                    "下载长度校验失败：期望 $expectedSize 字节，实际 $size 字节"
                )
            }
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                throw ContentVerificationException("下载 SHA-256 校验失败")
            }
            if (parent.findFile(fileName) != null) {
                throw LocalPathConflictException("下载期间目标文件已出现，不能覆盖：$localRelativePath")
            }
            if (!temp.renameTo(fileName)) {
                throw SafFileException("下载已校验，但临时文件重命名失败")
            }
            val renamed = parent.findFile(fileName)
                ?: throw SafFileException("下载文件重命名后无法读取")
            if (renamed.name != fileName || !renamed.isFile) {
                throw SafFileException("下载文件重命名后的名称或类型不正确")
            }
            renamed
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun resolveRoot(mapping: LocalFolderMapping): DocumentFile {
        if (mapping.treeUri.isBlank()) throw FolderAccessException("本地目录授权不存在，请重新选择目录")
        val root = try {
            DocumentFile.fromTreeUri(context, Uri.parse(mapping.treeUri))
        } catch (e: SecurityException) {
            throw FolderAccessException("本地目录授权已失效，请重新授权")
        } catch (e: Exception) {
            throw SafFileException("无法打开本地目录", e)
        }
        if (root == null || !root.exists() || !root.isDirectory || !root.canRead() || !root.canWrite()) {
            throw FolderAccessException("本地目录不存在、不可读写或授权已失效")
        }
        return root
    }

    private fun createParents(root: DocumentFile, segments: List<String>): DocumentFile {
        var current = root
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = if (existing != null) {
                if (!existing.isDirectory) throw SafFileException("本地路径的父级不是目录：$segment")
                existing
            } else {
                try {
                    current.createDirectory(segment)
                } catch (e: SecurityException) {
                    throw FolderAccessException("创建本地子目录时授权失效")
                } catch (e: Exception) {
                    throw SafFileException("创建本地子目录失败：$segment", e)
                } ?: throw SafFileException("创建本地子目录失败：$segment")
            }
        }
        return current
    }

    private fun validatedSegments(path: String): List<String> {
        val validation = RemotePath.validate(path)
        if (!validation.isValid || validation.normalized.isEmpty() || validation.normalized != path) {
            throw SyncPlanningException(validation.error ?: "本地相对路径无效")
        }
        return validation.normalized.split('/')
    }

    private fun openInput(document: DocumentFile): InputStream {
        return try {
            context.contentResolver.openInputStream(document.uri)
        } catch (e: SecurityException) {
            throw FolderAccessException("读取本地文件时授权失效")
        } catch (e: Exception) {
            throw SafFileException("无法打开本地文件：${document.name.orEmpty()}", e)
        } ?: throw SafFileException("无法打开本地文件：${document.name.orEmpty()}")
    }

    private fun skipFully(input: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining--
            } else {
                throw EOFException("本地文件在分块上传前已缩短")
            }
        }
    }

    private fun copyStream(src: DocumentFile, parent: DocumentFile, fileName: String) {
        val dest = try {
            parent.createFile("application/octet-stream", fileName)
        } catch (e: SecurityException) {
            throw FolderAccessException("创建移动目标文件时授权失效")
        } catch (e: Exception) {
            throw SafFileException("无法创建移动目标文件：$fileName", e)
        } ?: throw SafFileException("无法创建移动目标文件：$fileName")
        try {
            openInput(src).use { input ->
                val output = try {
                    context.contentResolver.openOutputStream(dest.uri, "w")
                } catch (e: SecurityException) {
                    throw FolderAccessException("写入移动目标文件时授权失效")
                } catch (e: Exception) {
                    throw SafFileException("无法写入移动目标文件：$fileName", e)
                } ?: throw SafFileException("无法打开移动目标文件输出流")
                output.use { stream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) stream.write(buffer, 0, count)
                    }
                    stream.flush()
                }
            }
        } catch (e: CancellationException) {
            dest.delete()
            throw e
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024

        /** 下载进度上报步长：每写入 256KB 更新一次任务进度 */
        const val PROGRESS_REPORT_STEP = 256L * 1024L
    }
}

class LocalPathConflictException(message: String) : Exception(message)
