package com.photosync.app.data.sync

import com.photosync.app.data.local.FileSyncStatus
import com.photosync.app.data.local.LocalFileIndex
import java.util.Locale

class SyncPlanningException(message: String) : IllegalArgumentException(message)

/** 映射根目录下的本地恢复区（应用内部目录），扫描时必须忽略。 */
internal const val LOCAL_TRASH_DIR_NAME = ".photosync-trash"

data class SyncMappingRule(
    val id: String,
    val onlyMedia: Boolean
)

data class DownloadPathPlan(
    val mappingId: String,
    val localRelativePath: String,
    val remoteRelativePath: String
)

object MediaFileFilter {
    private val extensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif",
        "mp4", "mov", "mkv", "avi", "webm", "3gp", "mpeg", "mpg", "m4v"
    )

    fun isMedia(mimeType: String?, name: String): Boolean {
        if (mimeType?.startsWith("image/") == true || mimeType?.startsWith("video/") == true) {
            return true
        }
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions
    }

    fun isTemporarySyncFile(name: String): Boolean =
        name.startsWith(".photosync-") && name.endsWith(".tmp")

    /**
     * Android 系统回收站条目：MediaStore 删除会把文件改名成 .trashed-<随机>-<时间戳>…，
     * 属于"系统已删除"的文件，不参与同步。
     */
    fun isSystemTrashed(name: String): Boolean = name.startsWith(".trashed-")

    /** 系统回收站目录（不同 ROM 大小写不一），整个目录跳过。 */
    fun isSystemTrashDir(name: String): Boolean =
        name.equals(".Trash", ignoreCase = true) || name.equals(".trash", ignoreCase = true)

    /** 路径任一段是系统回收站条目或目录时为 true。 */
    fun isSystemTrashPath(path: String): Boolean =
        path.split('/').any { isSystemTrashed(it) || isSystemTrashDir(it) }
}

object RemoteDownloadPlanner {
    /** 远端树与本地目录同构，映射根即空间根。 */
    fun plan(remotePath: String, mappings: List<SyncMappingRule>): DownloadPathPlan? {
        val validation = RemotePath.validate(remotePath)
        if (!validation.isValid || validation.normalized.isEmpty()) {
            throw SyncPlanningException(validation.error ?: "远端文件路径不能为空")
        }
        val normalized = validation.normalized
        // 兼容历史版本误上传的本地恢复区内容：这类路径不参与同步。
        if (normalized == LOCAL_TRASH_DIR_NAME || normalized.startsWith("$LOCAL_TRASH_DIR_NAME/")) {
            return null
        }
        // 系统回收站内容（历史误上传等）不再下载同步。
        if (normalized.split('/').any { MediaFileFilter.isSystemTrashed(it) || MediaFileFilter.isSystemTrashDir(it) }) {
            return null
        }
        return mappings.firstOrNull { mapping ->
            !mapping.onlyMedia || MediaFileFilter.isMedia(null, normalized)
        }?.let { mapping ->
            DownloadPathPlan(mapping.id, normalized, normalized)
        }
    }

    fun remotePath(localRelativePath: String): String {
        val local = RemotePath.validate(localRelativePath)
        if (!local.isValid || local.normalized.isEmpty()) {
            throw SyncPlanningException(local.error ?: "本地相对路径不能为空")
        }
        return local.normalized
    }
}

object SyncEventPlanner {
    fun shouldSkipOwnEvent(
        eventDeviceId: String,
        currentDeviceId: String,
        indexedRemoteFileId: String?,
        indexedSha256: String?,
        eventFileId: String,
        eventSha256: String
    ): Boolean = eventDeviceId == currentDeviceId &&
        indexedRemoteFileId == eventFileId &&
        !indexedSha256.isNullOrBlank() &&
        indexedSha256.equals(eventSha256, ignoreCase = true)
}

enum class ExistingLocalDecision { DOWNLOAD, INDEX_ONLY, CONFLICT }

object ExistingLocalPlanner {
    fun decide(targetExists: Boolean, sameSize: Boolean, sameSha256: Boolean): ExistingLocalDecision = when {
        !targetExists -> ExistingLocalDecision.DOWNLOAD
        sameSize && sameSha256 -> ExistingLocalDecision.INDEX_ONLY
        else -> ExistingLocalDecision.CONFLICT
    }
}

object DecimalContract {
    private val decimal = Regex("[0-9]+")
    private val sha256 = Regex("[0-9a-fA-F]{64}")

    fun long(raw: String, field: String, allowZero: Boolean = true): Long {
        if (!decimal.matches(raw)) throw SyncPlanningException("$field 必须是十进制整数字符串")
        val value = raw.toLongOrNull()
            ?: throw SyncPlanningException("$field 超出 Long 范围")
        if (value < 0L || (!allowZero && value == 0L)) {
            throw SyncPlanningException(if (allowZero) "$field 不能为负数" else "$field 必须大于 0")
        }
        return value
    }

    fun int(raw: String, field: String, allowZero: Boolean = true): Int {
        val value = long(raw, field, allowZero)
        if (value > Int.MAX_VALUE) throw SyncPlanningException("$field 超出 Int 范围")
        return value.toInt()
    }

    fun revision(raw: String): String {
        long(raw, "revision")
        return raw
    }

    /** 仅比较已通过 revision() 校验的十进制字符串，避免先转浮点数。 */
    fun compareRevisions(left: String, right: String): Int {
        revision(left)
        revision(right)
        val a = left.trimStart('0').ifEmpty { "0" }
        val b = right.trimStart('0').ifEmpty { "0" }
        if (a.length != b.length) return a.length.compareTo(b.length)
        return a.compareTo(b)
    }

    fun requireSha256(raw: String): String {
        if (!sha256.matches(raw)) throw SyncPlanningException("sha256 必须是 64 位十六进制字符串")
        return raw.lowercase(Locale.ROOT)
    }
}

/** 本地扫描与上次索引之间的差异，驱动 Phase 4 的修改/删除/重命名任务规划。 */
sealed class LocalChange {
    /** 本地新增文件，无远端对应 → 上传并提交 file_created。 */
    data class UploadNew(
        val relativePath: String,
        val size: Long,
        val sha256: String?
    ) : LocalChange()

    /** 已同步文件内容变化 → 上传新版本并提交 file_updated（baseVersion = 远端当前版本）。 */
    data class Modify(
        val relativePath: String,
        val fileId: String,
        val baseVersion: Int,
        val size: Long,
        val sha256: String?
    ) : LocalChange()

    /** 已同步文件在本地消失且未匹配到重命名 → 提交 file_deleted。 */
    data class Delete(
        val relativePath: String,
        val fileId: String,
        val baseVersion: Int
    ) : LocalChange()

    /** 已同步文件在本地改路径且内容一致 → 提交 file_renamed，保留 fileId 与版本。 */
    data class Rename(
        val oldRelativePath: String,
        val newRelativePath: String,
        val fileId: String,
        val baseVersion: Int,
        val size: Long,
        val sha256: String?
    ) : LocalChange()
}

/**
 * 比较扫描前的索引快照与扫描后的索引，产出需要执行的本地变更。
 * 仅对状态为 SYNCED 且有 remoteFileId 的文件感知修改/删除；本地仅有文件由 UploadNew 覆盖。
 * 重命名通过内容（sha256 + size）唯一配对识别，配对不唯一时回退为删除 + 新增上传。
 */
object LocalChangeDetector {
    /** 单次扫描允许自动提交的最大删除数；超过视为异常（如外部存储被卸载），不向服务器传播删除。 */
    const val DEFAULT_MAX_AUTO_DELETIONS = 50

    fun detect(
        oldFiles: List<LocalFileIndex>,
        newFiles: List<LocalFileIndex>,
        maxAutoDeletions: Int = DEFAULT_MAX_AUTO_DELETIONS
    ): List<LocalChange> {
        val oldByPath = oldFiles.associateBy { it.relativePath }
        val newByPath = newFiles.associateBy { it.relativePath }
        val changes = mutableListOf<LocalChange>()
        val deletions = mutableListOf<LocalChange.Delete>()

        for ((path, newFile) in newByPath) {
            val oldFile = oldByPath[path] ?: continue
            val oldFileId = oldFile.remoteFileId
            if (oldFile.status == FileSyncStatus.SYNCED &&
                oldFileId != null &&
                contentChanged(oldFile, newFile)
            ) {
                changes.add(
                    LocalChange.Modify(
                        relativePath = path,
                        fileId = oldFileId,
                        baseVersion = oldFile.remoteVersion ?: 1,
                        size = newFile.size,
                        sha256 = newFile.sha256
                    )
                )
            }
        }

        val removedSynced = oldByPath.filter { (path, old) ->
            path !in newByPath && old.status == FileSyncStatus.SYNCED && old.remoteFileId != null
        }.toMutableMap()

        val added = newByPath.filter { (path, _) -> path !in oldByPath }.toMutableMap()

        for ((oldPath, oldFile) in removedSynced.toMap()) {
            val matches = added.filterValues { newFile -> sameContent(oldFile, newFile) }
            if (matches.size == 1) {
                val (newPath, newFile) = matches.entries.first()
                changes.add(
                    LocalChange.Rename(
                        oldRelativePath = oldPath,
                        newRelativePath = newPath,
                        fileId = oldFile.remoteFileId!!,
                        baseVersion = oldFile.remoteVersion ?: 1,
                        size = newFile.size,
                        sha256 = newFile.sha256
                    )
                )
                removedSynced.remove(oldPath)
                added.remove(newPath)
            }
        }

        for ((path, oldFile) in removedSynced) {
            deletions.add(
                LocalChange.Delete(
                    relativePath = path,
                    fileId = oldFile.remoteFileId!!,
                    baseVersion = oldFile.remoteVersion ?: 1
                )
            )
        }
        // 大量删除保护：删除数超过阈值时不提交删除（保留远端文件），避免目录被卸载等误删扩散。
        if (deletions.size <= maxAutoDeletions) {
            changes.addAll(deletions)
        }
        for ((path, newFile) in added) {
            changes.add(LocalChange.UploadNew(path, newFile.size, newFile.sha256))
        }

        return changes
    }

    private fun contentChanged(old: LocalFileIndex, new: LocalFileIndex): Boolean {
        if (old.size != new.size) return true
        val oldSha = old.sha256
        val newSha = new.sha256
        if (oldSha.isNullOrBlank() || newSha.isNullOrBlank()) return false
        return !oldSha.equals(newSha, ignoreCase = true)
    }

    private fun sameContent(a: LocalFileIndex, b: LocalFileIndex): Boolean {
        if (a.size != b.size) return false
        val aSha = a.sha256
        val bSha = b.sha256
        if (aSha.isNullOrBlank() || bSha.isNullOrBlank()) return false
        return aSha.equals(bSha, ignoreCase = true)
    }
}
