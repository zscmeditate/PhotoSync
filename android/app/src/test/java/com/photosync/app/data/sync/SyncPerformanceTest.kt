package com.photosync.app.data.sync

import com.photosync.app.data.local.FileSyncStatus
import com.photosync.app.data.local.LocalFileIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能冒烟：2 万个索引项的差异检测应在宽松预算内完成，
 * 同时验证重命名配对与大量删除保护在大数据量下仍正确。
 */
class SyncPerformanceTest {

    @Test
    fun detectsChangesForLargeIndexWithinBudget() {
        val files = (0 until 20_000).map { index ->
            LocalFileIndex(
                id = index.toLong(),
                mappingId = "mapping",
                relativePath = "dir/$index.jpg",
                fileName = "$index.jpg",
                size = 1024L + index,
                modifiedAt = index.toLong(),
                sha256 = String.format("%064x", index),
                docUri = "content://test/$index",
                status = FileSyncStatus.SYNCED,
                lastScannedAt = 0L,
                remoteFileId = "file-$index",
                remoteVersion = 1
            )
        }
        // 每 1000 个文件重命名一个（内容不变），其余保持不变。
        val newFiles = files.mapIndexed { index, file ->
            if (index % 1000 == 0) file.copy(relativePath = "dir/renamed-$index.jpg") else file
        }

        val started = System.nanoTime()
        val changes = LocalChangeDetector.detect(files, newFiles)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(20, changes.filterIsInstance<LocalChange.Rename>().size)
        assertTrue("差异检测耗时过长：${elapsedMillis}ms", elapsedMillis < 15_000)
    }

    @Test
    fun bulkDeletionOnLargeIndex_requiresUserConfirmation() {
        val files = (0 until 20_000).map { index ->
            LocalFileIndex(
                id = index.toLong(),
                mappingId = "mapping",
                relativePath = "dir/$index.jpg",
                fileName = "$index.jpg",
                size = 1024L,
                modifiedAt = 0L,
                sha256 = String.format("%064x", index),
                docUri = "content://test/$index",
                status = FileSyncStatus.SYNCED,
                lastScannedAt = 0L,
                remoteFileId = "file-$index",
                remoteVersion = 1
            )
        }
        // 模拟整目录被移走：删除数远超阈值，检测如实识别，由策略要求用户二次确认后才删除云端。
        val changes = LocalChangeDetector.detect(files, emptyList())
        val deletions = changes.filterIsInstance<LocalChange.Delete>()

        assertEquals(20_000, deletions.size)
        assertTrue(BulkDeletionPolicy.requiresConfirmation(deletions.size))
    }
}
