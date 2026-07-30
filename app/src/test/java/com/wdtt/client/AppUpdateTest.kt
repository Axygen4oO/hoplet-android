package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun comparesVersionNumbersNumerically() {
        assertTrue(isNewerVersion("1.9", "1.10"))
        assertTrue(isNewerVersion("1.10", "1.11"))
        assertFalse(isNewerVersion("1.11", "1.9"))
        assertFalse(isNewerVersion("v1.10", "1.10"))
    }

    @Test
    fun handlesPrereleaseVersionsPredictably() {
        assertFalse(isNewerVersion("1.4.4", "1.4.4-beta1"))
        assertTrue(isNewerVersion("1.4.4", "1.4.4-beta1", includePrerelease = true))
        assertTrue(isNewerVersion("1.4.4-beta1", "1.4.4"))
        assertTrue(isNewerVersion("1.4.4-beta1", "1.4.4-beta2", includePrerelease = true))
    }

    @Test
    fun extractsSha256ForMatchingApkWhenSeveralHashesArePresent() {
        val otherHash = "1111111111111111111111111111111111111111111111111111111111111111"
        val targetHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val text = """
            SHA256 (hoplet-armeabi-v7a.apk) = $otherHash
            SHA256 (hoplet-universal.apk) = $targetHash
        """.trimIndent()

        assertEquals(targetHash, extractSha256FromText(text, "hoplet-universal.apk"))
    }

    @Test
    fun returnsNullWhenSha256IsMissing() {
        assertNull(extractSha256FromText("release notes without checksum", "hoplet-universal.apk"))
    }
}
