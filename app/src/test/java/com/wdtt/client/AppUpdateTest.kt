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

    @Test
    fun comparesVersionCodeBeforeVersionName() {
        val release = AppReleaseInfo(
            versionTag = "v1.5.0",
            releaseUrl = "https://example.test/release",
            source = RemoteVersionSource.Release,
            versionName = "1.5.0",
            versionCode = 47L,
        )
        assertTrue(isNewerRelease("1.4.9", 46L, release))
        assertFalse(isNewerRelease("9.9.9", 47L, release))
    }

    @Test
    fun parsesUpdateManifestAndRejectsMalformedJson() {
        val hash = "a".repeat(64)
        val manifest = parseUpdateManifest(
            """{"versionName":"1.5.0","versionCode":47,"tag":"v1.5.0","apk":"app-release.apk","sha256":"$hash","mandatory":false}"""
        )
        assertEquals(47L, manifest?.versionCode)
        assertEquals(hash, manifest?.sha256)
        assertNull(parseUpdateManifest("{broken"))
    }

    @Test
    fun invalidManifestHashIsNotTrusted() {
        val manifest = parseUpdateManifest(
            """{"versionName":"1.5.0","versionCode":47,"sha256":"not-a-hash"}"""
        )
        assertNull(manifest)
    }
}
