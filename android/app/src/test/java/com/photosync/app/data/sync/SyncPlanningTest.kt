package com.photosync.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncPlanningTest {

    @Test
    fun remotePath_supportsRootAndNestedMappings() {
        assertEquals("photo.jpg", RemoteDownloadPlanner.remotePath("", "photo.jpg"))
        assertEquals(
            "Camera/2026/photo.jpg",
            RemoteDownloadPlanner.remotePath("Camera", "2026/photo.jpg")
        )

        val root = SyncMappingRule("root", "", includeSubdirs = true, onlyMedia = false)
        val nested = SyncMappingRule("camera", "Camera", includeSubdirs = true, onlyMedia = false)
        assertEquals(
            DownloadPathPlan("root", "2026/photo.jpg", "2026/photo.jpg"),
            RemoteDownloadPlanner.plan("2026/photo.jpg", listOf(root))
        )
        assertEquals(
            DownloadPathPlan("camera", "2026/photo.jpg", "Camera/2026/photo.jpg"),
            RemoteDownloadPlanner.plan("Camera/2026/photo.jpg", listOf(root, nested))
        )
    }

    @Test
    fun plan_ignoresUnrelatedMappingsAndUsesLongestPrefix() {
        val mappings = listOf(
            SyncMappingRule("root", "", true, false),
            SyncMappingRule("camera", "Camera", true, false),
            SyncMappingRule("year", "Camera/2026", true, false)
        )
        assertEquals("year", RemoteDownloadPlanner.plan("Camera/2026/a.jpg", mappings)?.mappingId)
        assertNull(RemoteDownloadPlanner.plan("Other/a.jpg", mappings.drop(1)))
        assertNull(RemoteDownloadPlanner.plan("Camera2/a.jpg", listOf(mappings[1])))
    }

    @Test
    fun plan_excludesRemotePathEqualToDirectoryPrefix() {
        val mapping = SyncMappingRule("camera", "Camera", includeSubdirs = true, onlyMedia = false)
        assertNull(RemoteDownloadPlanner.plan("Camera", listOf(mapping)))
    }

    @Test
    fun plan_respectsSubdirectoryAndMediaRules() {
        val flat = SyncMappingRule("flat", "Camera", includeSubdirs = false, onlyMedia = false)
        assertEquals("a.jpg", RemoteDownloadPlanner.plan("Camera/a.jpg", listOf(flat))?.localRelativePath)
        assertNull(RemoteDownloadPlanner.plan("Camera/2026/a.jpg", listOf(flat)))

        val media = flat.copy(includeSubdirs = true, onlyMedia = true)
        assertEquals("2026/a.heic", RemoteDownloadPlanner.plan("Camera/2026/a.heic", listOf(media))?.localRelativePath)
        assertEquals("2026/a.mp4", RemoteDownloadPlanner.plan("Camera/2026/a.mp4", listOf(media))?.localRelativePath)
        assertNull(RemoteDownloadPlanner.plan("Camera/2026/notes.txt", listOf(media)))
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
            RemoteDownloadPlanner.remotePath("Camera", "../private.jpg")
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
