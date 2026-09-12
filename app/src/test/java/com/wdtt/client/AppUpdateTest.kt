package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AppUpdateTest {
    @Test
    fun releaseWinsOverTagForTheSameVersionAndKeepsDirectApkUrl() {
        val release = downloadableRelease("v1.5.0", "Release notes")
        val tag = AppReleaseInfo(
            versionTag = "v1.5.0",
            releaseUrl = "https://github.com/Axygen4oO/hoplet-android/tree/v1.5.0",
            source = RemoteVersionSource.Tag,
        )

        val selected = selectOtaCandidate(release, tag)

        assertEquals(RemoteVersionSource.Release, selected?.source)
        assertEquals(release.downloadUrl, selected?.downloadUrl)
    }

    @Test
    fun newerTagDoesNotBecomeInstallableOtaCandidate() {
        val release = downloadableRelease("v1.4.9", "Release notes")
        val newerTag = AppReleaseInfo(
            versionTag = "v1.5.0",
            releaseUrl = "https://github.com/Axygen4oO/hoplet-android/tree/v1.5.0",
            source = RemoteVersionSource.Tag,
        )

        val selected = selectOtaCandidate(release, newerTag)

        assertEquals("v1.4.9", selected?.versionTag)
        assertTrue(isInstallableOtaRelease(selected!!))
        assertFalse(isInstallableOtaRelease(newerTag))
    }

    @Test
    fun releaseWithoutApkIsNotInstallable() {
        val release = AppReleaseInfo(
            versionTag = "v1.5.0",
            releaseUrl = "https://github.com/Axygen4oO/hoplet-android/releases/tag/v1.5.0",
            source = RemoteVersionSource.Release,
            releaseNotes = "Notes",
        )

        assertNull(selectOtaCandidate(release))
    }

    @Test
    fun missingBrowserDownloadUrlIsRebuiltFromReleaseAsset() {
        val json = JSONObject(
            """
            {
              "tag_name": "v1.5.0",
              "html_url": "https://github.com/Axygen4oO/hoplet-android/releases/tag/v1.5.0",
              "body": "Release notes",
              "draft": false,
              "prerelease": false,
              "assets": [{"name":"app-release.apk","size":42}]
            }
            """.trimIndent()
        )

        val release = json.toAppReleaseInfo()

        assertEquals(
            "https://github.com/Axygen4oO/hoplet-android/releases/download/v1.5.0/app-release.apk",
            release?.downloadUrl,
        )
    }

    @Test
    fun tagMetadataCannotEraseReleaseNotes() {
        clearReleaseNotesCacheForTests()
        val release = downloadableRelease("v1.5.0", "Important release notes")
        val tag = AppReleaseInfo(
            versionTag = "v1.5.0",
            releaseUrl = "https://github.com/Axygen4oO/hoplet-android/tree/v1.5.0",
            source = RemoteVersionSource.Tag,
            releaseNotes = "",
        )

        val merged = mergeReleaseInfo(release, tag)

        assertEquals(RemoteVersionSource.Release, merged.source)
        assertEquals(release.downloadUrl, merged.downloadUrl)
        assertEquals("Important release notes", merged.releaseNotes)
    }

    @Test
    fun releaseMetadataWinsOverTagMetadataForTheSameVersion() {
        val tag = AppReleaseInfo(
            versionTag = "v1.5.0",
            releaseUrl = "https://github.com/Axygen4oO/hoplet-android/tree/v1.5.0",
            source = RemoteVersionSource.Tag,
        )
        val release = downloadableRelease("v1.5.0", "Release notes")

        val merged = mergeReleaseInfo(tag, release)

        assertEquals(RemoteVersionSource.Release, merged.source)
        assertEquals(release.downloadUrl, merged.downloadUrl)
    }

    @Test
    fun emptyRefreshDoesNotEraseCachedReleaseNotes() {
        clearReleaseNotesCacheForTests()
        val first = AppReleaseInfo("v1.5.0", "https://example.test/release", RemoteVersionSource.Release, releaseNotes = "Fixed connection stability.")
        val second = first.copy(releaseNotes = "")

        val loaded = mergeReleaseInfo(null, first)
        val refreshed = mergeReleaseInfo(loaded, second)

        assertEquals("Fixed connection stability.", refreshed.releaseNotes)
    }

    @Test
    fun releaseNotesAreBoundToVersion() {
        clearReleaseNotesCacheForTests()
        val a = AppReleaseInfo("v1.4.9", "https://example.test/a", RemoteVersionSource.Release, releaseNotes = "Notes A")
        val b = AppReleaseInfo("v1.5.0", "https://example.test/b", RemoteVersionSource.Release, releaseNotes = "Notes B")

        mergeReleaseInfo(null, a)
        val mergedB = mergeReleaseInfo(a, b)

        assertEquals("Notes B", mergedB.releaseNotes)
    }

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
    fun availableUpdateLabelUsesOnlyNewerRemoteRelease() {
        val same = AppReleaseInfo("v1.4.9", "https://example.test/same", RemoteVersionSource.Release, versionCode = 46L)
        val newer = AppReleaseInfo("v1.5.0", "https://example.test/new", RemoteVersionSource.Release, versionCode = 47L)
        val older = AppReleaseInfo("v1.4.8", "https://example.test/old", RemoteVersionSource.Release, versionCode = 45L)

        assertNull(availableUpdateLabel("1.4.9", 46L, same))
        assertEquals("Доступна новая версия 1.5.0", availableUpdateLabel("1.4.9", 46L, newer))
        assertNull(availableUpdateLabel("1.5.0", 47L, older))
        assertNull(availableUpdateLabel("1.4.9", 46L, null))
    }

    @Test
    fun availableUpdateLabelFallsBackToSemanticVersionAndStripsV() {
        val remote = AppReleaseInfo(
            versionTag = "v1.5.0",
            releaseUrl = "https://example.test/release",
            source = RemoteVersionSource.Release,
            versionName = null,
            versionCode = null,
        )

        assertEquals("Доступна новая версия 1.5.0", availableUpdateLabel("1.4.9", 46L, remote))
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

    @Test
    fun manifestApkFilenameResolvesToDirectGithubAssetUrl() {
        val release = AppReleaseInfo("v1.5.0", "https://github.com/Axygen4oO/hoplet-android/releases/tag/v1.5.0", RemoteVersionSource.Release)
        val resolved = resolveManifestApkUrl(release, "app-release.apk")
        assertEquals("https://github.com/Axygen4oO/hoplet-android/releases/download/v1.5.0/app-release.apk", resolved?.first)
        assertEquals("app-release.apk", resolved?.second)
        assertFalse(resolved!!.first.contains("/releases/tag/"))
    }

    @Test
    fun directManifestUrlIsKeptAndReleasePageIsNeverUsedForDownload() {
        val release = AppReleaseInfo("v1.5.0", "https://github.com/Axygen4oO/hoplet-android/releases/tag/v1.5.0", RemoteVersionSource.Release)
        val resolved = resolveManifestApkUrl(release, "https://example.test/app-release.apk")
        assertEquals("https://example.test/app-release.apk", resolved?.first)
    }

    private fun downloadableRelease(versionTag: String, notes: String): AppReleaseInfo =
        AppReleaseInfo(
            versionTag = versionTag,
            releaseUrl = "https://github.com/Axygen4oO/hoplet-android/releases/tag/$versionTag",
            source = RemoteVersionSource.Release,
            downloadUrl = "https://github.com/Axygen4oO/hoplet-android/releases/download/$versionTag/app-release.apk",
            releaseNotes = notes,
            downloadFileName = "app-release.apk",
        )
}
