package com.photosync.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {

    @Test
    fun normalize_matchesServerRules() {
        assertEquals("Camera/2026", RemotePath.normalize("  \\Camera//2026/  "))
        assertEquals("", RemotePath.normalize(" /// "))
    }

    @Test
    fun validate_rejectsUnsafeOrLongPaths() {
        assertTrue(RemotePath.validate("Camera/../private").error != null)
        assertTrue(RemotePath.validate("Camera/\u0001bad").error != null)
        assertTrue(RemotePath.validate("a".repeat(129)).error != null)
        assertTrue(RemotePath.validate("a".repeat(513)).error != null)
        assertTrue(RemotePath.validate("Camera/2026").isValid)
        assertTrue(RemotePath.validate("").isValid)
    }
}
