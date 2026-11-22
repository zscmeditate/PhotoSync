package com.photosync.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePrefixTest {

    @Test
    fun normalize_matchesServerRules() {
        assertEquals("Camera/2026", RemotePrefix.normalize("  \\Camera//2026/  "))
        assertEquals("", RemotePrefix.normalize(" /// "))
    }

    @Test
    fun validate_rejectsUnsafeOrLongPaths() {
        assertTrue(RemotePrefix.validate("Camera/../private").error != null)
        assertTrue(RemotePrefix.validate("Camera/\u0001bad").error != null)
        assertTrue(RemotePrefix.validate("a".repeat(129)).error != null)
        assertTrue(RemotePrefix.validate("a".repeat(513)).error != null)
        assertTrue(RemotePrefix.validate("Camera/2026").isValid)
        assertTrue(RemotePrefix.validate("").isValid)
    }

    @Test
    fun overlaps_includesRootsAndDescendantsOnly() {
        assertTrue(RemotePrefix.overlaps("", "Camera"))
        assertTrue(RemotePrefix.overlaps("Camera", "Camera"))
        assertTrue(RemotePrefix.overlaps("Camera", "Camera/2026"))
        assertTrue(RemotePrefix.overlaps("Camera/2026", "Camera"))
        assertFalse(RemotePrefix.overlaps("Camera", "Camera2"))
        assertFalse(RemotePrefix.overlaps("Camera/A", "Camera/B"))
    }
}
