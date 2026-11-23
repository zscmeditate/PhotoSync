package com.photosync.app.data.sync

import com.photosync.app.data.local.FileSyncStatus
import com.photosync.app.data.local.LocalFileIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChangeDetectorTest {

    private fun index(
        relativePath: String,
        size: Long = 10,
        sha256: String? = "a".repeat(64),
        status: String = FileSyncStatus.SYNCED,
        remoteFileId: String? = null,
        remoteVersion: Int? = null
    ) = LocalFileIndex(
        mappingId = "m",
        relativePath = relativePath,
        fileName = relativePath.substringAfterLast('/'),
        size = size,
        modifiedAt = 0L,
        sha256 = sha256,
        docUri = "content://doc/$relativePath",
        status = status,
        lastScannedAt = 0L,
        remoteFileId = remoteFileId,
        remoteVersion = remoteVersion
    )

    @Test
    fun unchangedSyncedFile_producesNoChange() {
        val old = listOf(index("a.jpg", remoteFileId = "f1", remoteVersion = 1))
        val new = listOf(index("a.jpg", remoteFileId = "f1", remoteVersion = 1))
        assertTrue(LocalChangeDetector.detect(old, new).isEmpty())
    }

    @Test
    fun modifiedSyncedFile_producesModifyWithBaseVersion() {
        val old = listOf(index("a.jpg", sha256 = "a".repeat(64), remoteFileId = "f1", remoteVersion = 2))
        val new = listOf(index("a.jpg", sha256 = "b".repeat(64)))
        val changes = LocalChangeDetector.detect(old, new)
        assertEquals(1, changes.size)
        val modify = changes.single() as LocalChange.Modify
        assertEquals("a.jpg", modify.relativePath)
        assertEquals("f1", modify.fileId)
        assertEquals(2, modify.baseVersion)
    }

    @Test
    fun modifiedWithoutSha_isNotReportedAsModify() {
        // size 相同、双方 sha 都缺失时无法可靠判断内容变化，不应误报修改
        val old = listOf(index("a.jpg", sha256 = null, size = 10, remoteFileId = "f1", remoteVersion = 1))
        val new = listOf(index("a.jpg", sha256 = null, size = 10))
        assertTrue(LocalChangeDetector.detect(old, new).isEmpty())
    }

    @Test
    fun newFile_producesUploadNew() {
        val changes = LocalChangeDetector.detect(emptyList(), listOf(index("a.jpg")))
        val upload = changes.single() as LocalChange.UploadNew
        assertEquals("a.jpg", upload.relativePath)
    }


    @Test
    fun removedSyncedFile_producesDelete() {
        val old = listOf(index("a.jpg", remoteFileId = "f1", remoteVersion = 3))
        val changes = LocalChangeDetector.detect(old, emptyList())
        val delete = changes.single() as LocalChange.Delete
        assertEquals("a.jpg", delete.relativePath)
        assertEquals("f1", delete.fileId)
        assertEquals(3, delete.baseVersion)
    }

    @Test
    fun removedLocalOnlyFile_producesNoChange() {
        val old = listOf(index("a.jpg", status = FileSyncStatus.PENDING_UPLOAD, remoteFileId = null))
        assertTrue(LocalChangeDetector.detect(old, emptyList()).isEmpty())
    }

    @Test
    fun renamedFile_pairsByContentAndPreservesFileId() {
        val old = listOf(index("a.jpg", remoteFileId = "f1", remoteVersion = 1))
        val new = listOf(index("b.jpg"))
        val changes = LocalChangeDetector.detect(old, new)
        assertEquals(1, changes.size)
        val rename = changes.single() as LocalChange.Rename
        assertEquals("a.jpg", rename.oldRelativePath)
        assertEquals("b.jpg", rename.newRelativePath)
        assertEquals("f1", rename.fileId)
        assertEquals(1, rename.baseVersion)
    }

    @Test
    fun renameWithDifferentContent_fallsBackToDeleteAndUploadNew() {
        val old = listOf(index("a.jpg", sha256 = "a".repeat(64), remoteFileId = "f1", remoteVersion = 1))
        val new = listOf(index("b.jpg", sha256 = "b".repeat(64)))
        val changes = LocalChangeDetector.detect(old, new)
        assertEquals(2, changes.size)
        assertTrue(changes.any { it is LocalChange.Delete })
        assertTrue(changes.any { it is LocalChange.UploadNew })
        assertTrue(changes.none { it is LocalChange.Rename })
    }

    @Test
    fun multipleRemovedSameContent_doesNotDoublePairTarget() {
        val old = listOf(
            index("a.jpg", sha256 = "a".repeat(64), remoteFileId = "f1", remoteVersion = 1),
            index("c.jpg", sha256 = "a".repeat(64), remoteFileId = "f2", remoteVersion = 1)
        )
        val new = listOf(index("b.jpg", sha256 = "a".repeat(64)))
        val changes = LocalChangeDetector.detect(old, new)
        // 同一目标只被消费一次：恰好一个 Rename、一个 Delete，没有 UploadNew 重复上传
        assertEquals(1, changes.count { it is LocalChange.Rename })
        assertEquals(1, changes.count { it is LocalChange.Delete })
        assertEquals(0, changes.count { it is LocalChange.UploadNew })
    }

    @Test
    fun sizeChangeWithMatchingSha_isReportedAsModify() {
        val old = listOf(index("a.jpg", size = 10, sha256 = "a".repeat(64), remoteFileId = "f1", remoteVersion = 1))
        val new = listOf(index("a.jpg", size = 20, sha256 = "a".repeat(64)))
        val changes = LocalChangeDetector.detect(old, new)
        assertEquals(1, changes.count { it is LocalChange.Modify })
    }

    @Test
    fun bulkDeleteOverThreshold_isSuppressed() {
        val old = (1..3).map { index("d$it.jpg", remoteFileId = "f$it", remoteVersion = 1) }
        val changes = LocalChangeDetector.detect(old, emptyList(), maxAutoDeletions = 2)
        assertEquals(0, changes.count { it is LocalChange.Delete })
    }

    @Test
    fun bulkDeleteAtThreshold_isAllowed() {
        val old = (1..3).map { index("d$it.jpg", remoteFileId = "f$it", remoteVersion = 1) }
        val changes = LocalChangeDetector.detect(old, emptyList(), maxAutoDeletions = 3)
        assertEquals(3, changes.count { it is LocalChange.Delete })
    }
}
