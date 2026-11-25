package com.photosync.app.data.sync

import com.photosync.app.data.local.FileSyncStatus
import com.photosync.app.data.local.LocalFileIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncPlanningTest {

    @Test
    fun remotePath_matchesLocalStructure() {
        assertEquals("photo.jpg", RemoteDownloadPlanner.remotePath("photo.jpg"))
        assertEquals(
            "Camera/2026/photo.jpg",
            RemoteDownloadPlanner.remotePath("  \\Camera//2026/photo.jpg ")
        )

        val root = SyncMappingRule("root", onlyMedia = false)
        assertEquals(
            DownloadPathPlan("root", "2026/photo.jpg", "2026/photo.jpg"),
            RemoteDownloadPlanner.plan("2026/photo.jpg", listOf(root))
        )
    }

    @Test
    fun systemTrash_detectsNamesAndSafDocumentIds() {
        assertTrue(MediaFileFilter.isSystemTrashed(".trashed-1803299579-20年吉利帝豪GL2 (1).mp4"))
        // SAF 文档 ID 不是纯文件名，前缀检查会失效，必须按 ':' 与 '/' 分段判断
        assertTrue(
            MediaFileFilter.isSystemTrashPath(
                "primary:Download/.trashed-1803299579-20年吉利帝豪GL2 (1).mp4"
            )
        )
        assertTrue(MediaFileFilter.isSystemTrashPath("Download/.Trash/a.jpg"))
        assertFalse(MediaFileFilter.isSystemTrashPath("Download/20年吉利帝豪GL2 (1).mp4"))
        assertFalse(MediaFileFilter.isSystemTrashPath("primary:DCIM/Camera/IMG_0001.jpg"))
    }

    @Test
    fun dedupeRemoteBindings_resetsExtraCopyToPendingUpload() {
        fun file(id: Long, path: String, fileId: String?, version: Int?) = LocalFileIndex(
            id = id,
            mappingId = "m",
            relativePath = path,
            fileName = path,
            size = 1,
            modifiedAt = 0,
            sha256 = "s",
            docUri = "",
            status = FileSyncStatus.SYNCED,
            lastScannedAt = 0,
            remoteFileId = fileId,
            remoteVersion = version
        )
        val copy = file(2, "b.mp4", "f", null)
        val original = file(1, "a.mp4", "f", 1)
        val result = dedupeRemoteBindings(listOf(copy, original))

        // 保留有远端版本的原始绑定，另一条重置为待上传（会作为新文件上传）
        assertEquals("a.mp4", result[1].relativePath)
        assertEquals("f", result[1].remoteFileId)
        assertNull(result[0].remoteFileId)
        assertEquals(FileSyncStatus.PENDING_UPLOAD, result[0].status)
    }

    @Test
    fun needsRemoteReconcile_onlyWhenNoFileIsBoundToRemote() {
        fun file(
            path: String,
            fileId: String?,
            status: String = if (fileId == null) FileSyncStatus.PENDING_UPLOAD else FileSyncStatus.SYNCED
        ) =
            LocalFileIndex(
                id = 0,
                mappingId = "m",
                relativePath = path,
                fileName = path,
                size = 1,
                modifiedAt = 0,
                sha256 = "s",
                docUri = "",
                status = status,
                lastScannedAt = 0,
                remoteFileId = fileId,
                remoteVersion = null
            )

        assertFalse(needsRemoteReconcile(emptyList()))
        // 重装/重新绑定后绑定目录时已先扫描一次：索引非空但没有任何远端关联
        assertTrue(needsRemoteReconcile(listOf(file("a.jpg", null), file("b.jpg", null))))
        // 已有部分远端关联属于正常增量同步，不再整体核对
        assertFalse(needsRemoteReconcile(listOf(file("a.jpg", "f1"), file("b.jpg", null))))
        // 存在远端关联时即便个别记录是失败状态也不整体核对（失败由任务重试处理）
        assertFalse(
            needsRemoteReconcile(
                listOf(
                    file("a.jpg", "f1"),
                    file("b.jpg", null, FileSyncStatus.SYNC_ERROR)
                )
            )
        )
    }

    @Test
    fun plan_skipsSystemTrashAndRespectsMediaRule() {
        val media = SyncMappingRule("root", onlyMedia = true)
        assertEquals(
            DownloadPathPlan("root", "2026/a.heic", "2026/a.heic"),
            RemoteDownloadPlanner.plan("2026/a.heic", listOf(media))
        )
        assertEquals(
            DownloadPathPlan("root", "2026/a.mp4", "2026/a.mp4"),
            RemoteDownloadPlanner.plan("2026/a.mp4", listOf(media))
        )
        assertNull(RemoteDownloadPlanner.plan("2026/notes.txt", listOf(media)))
        // 系统回收站内容（历史误上传）不下载
        assertNull(
            RemoteDownloadPlanner.plan(
                "Download/.trashed-1803299579-a.mp4",
                listOf(SyncMappingRule("root", onlyMedia = false))
            )
        )
        assertNull(
            RemoteDownloadPlanner.plan(
                ".Trash/old.jpg",
                listOf(SyncMappingRule("root", onlyMedia = false))
            )
        )
        assertNull(RemoteDownloadPlanner.plan("photo.jpg", emptyList()))
    }

    @Test
    fun ownEvent_isSkippedOnlyWhenRemoteIdentityAndContentMatch() {
        assertTrue(
            SyncEventPlanner.shouldSkipOwnEvent(
                "device", "device", "file", "ABC", "file", "abc"
            )
        )
        assertFalse(
            SyncEventPlanner.shouldSkipOwnEvent(
                "other", "device", "file", "abc", "file", "abc"
            )
        )
        assertFalse(
            SyncEventPlanner.shouldSkipOwnEvent(
                "device", "device", "old-file", "abc", "file", "abc"
            )
        )
        assertFalse(
            SyncEventPlanner.shouldSkipOwnEvent(
                "device", "device", "file", null, "file", "abc"
            )
        )
    }

    @Test
    fun existingLocal_neverOverwritesDifferentContent() {
        assertEquals(
            ExistingLocalDecision.DOWNLOAD,
            ExistingLocalPlanner.decide(false, sameSize = false, sameSha256 = false)
        )
        assertEquals(
            ExistingLocalDecision.INDEX_ONLY,
            ExistingLocalPlanner.decide(true, sameSize = true, sameSha256 = true)
        )
        assertEquals(
            ExistingLocalDecision.CONFLICT,
            ExistingLocalPlanner.decide(true, sameSize = true, sameSha256 = false)
        )
        assertEquals(
            ExistingLocalDecision.CONFLICT,
            ExistingLocalPlanner.decide(true, sameSize = false, sameSha256 = true)
        )
    }

    @Test
    fun decimalContract_validatesStringsAndComparesRevisionsWithoutFloatingPoint() {
        assertEquals(0L, DecimalContract.long("0", "size"))
        assertEquals(Long.MAX_VALUE, DecimalContract.long(Long.MAX_VALUE.toString(), "size"))
        assertTrue(DecimalContract.compareRevisions("0009", "10") < 0)
        assertTrue(DecimalContract.compareRevisions("9007199254740993", "9007199254740992") > 0)
        assertEquals(0, DecimalContract.compareRevisions("00042", "42"))
        assertEquals("0012", DecimalContract.revision("0012"))

        for (invalid in listOf("", "-1", "+1", " 1", "1.0", "1e3", "9223372036854775808")) {
            try {
                DecimalContract.long(invalid, "size")
                fail("应拒绝非法十进制字符串：$invalid")
            } catch (_: SyncPlanningException) {
                // expected
            }
        }
    }

    @Test
    fun pathsAndHashes_rejectUnsafeValues() {
        try {
            RemoteDownloadPlanner.remotePath("../private.jpg")
            fail("应拒绝目录穿越")
        } catch (_: SyncPlanningException) {
            // expected
        }
        try {
            DecimalContract.requireSha256("abc")
            fail("应拒绝非法 SHA-256")
        } catch (_: SyncPlanningException) {
            // expected
        }
        assertEquals(
            "a".repeat(64),
            DecimalContract.requireSha256("A".repeat(64))
        )
    }
}
