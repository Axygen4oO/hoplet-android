package com.wdtt.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Фикстуры OTA не зависят от сети и воспроизводят форму GitHub API. */
class AppUpdateFixtureMatrixTest {
    @Test
    fun releaseMatrixContainsAllRequiredMetadata() {
        otaFixtures.forEach { fixture ->
            val release = fixture.payload.toAppReleaseInfo()
            assertTrue("${fixture.tag} must be installable", release != null)
            assertEquals(fixture.tag, release?.versionTag)
            assertEquals(fixture.body, release?.releaseNotes)
            assertEquals(fixture.apk, release?.downloadFileName)
            assertEquals(fixture.prerelease, release?.isPrerelease)
            assertEquals(fixture.draft, release?.isDraft)
            val manifest = parseUpdateManifest(fixture.manifest.toString())
            assertEquals(fixture.versionName, manifest?.versionName)
            assertEquals(fixture.versionCode, manifest?.versionCode)
            assertEquals(fixture.tag, manifest?.tag)
            assertEquals(fixture.apk, manifest?.apk)
            assertEquals(fixture.mandatory, manifest?.mandatory)
        }
    }

    @Test
    fun draftAndPrereleaseSelectionFollowBetaPolicy() {
        val stable = otaFixtures.first { it.tag == "v1.5.0" }.payload.toAppReleaseInfo()!!
        val draft = stable.copy(isDraft = true)
        val beta = stable.copy(isPrerelease = true)

        assertEquals(stable, selectOtaCandidate(stable))
        assertNull(selectOtaCandidate(draft))
        assertNull(selectOtaCandidate(beta))
        assertEquals(beta, selectOtaCandidate(beta, includePrerelease = true))
    }

    @Test
    fun updateUiStateCoversDownloadLifecycle() {
        val release = otaFixtures.first { it.tag == "v1.5.0" }.payload.toAppReleaseInfo()!!
        val downloading = AppUpdateDownloadSnapshot(
            phase = AppUpdatePhase.DOWNLOADING,
            versionTag = release.versionTag,
            releaseUrl = release.releaseUrl,
            downloadUrl = release.downloadUrl.orEmpty(),
            downloadFileName = release.downloadFileName.orEmpty(),
            downloadedBytes = 50,
            totalBytes = 100,
        ).toUpdateUiState()
        assertTrue(downloading is UpdateUiState.Downloading)
        assertEquals(0.5f, (downloading as UpdateUiState.Downloading).progress, 0.0001f)

        val verifying = AppUpdateDownloadSnapshot(
            phase = AppUpdatePhase.VERIFYING,
            versionTag = release.versionTag,
            releaseUrl = release.releaseUrl,
        ).toUpdateUiState(release)
        assertTrue(verifying is UpdateUiState.Verifying)

        val ready = AppUpdateDownloadSnapshot(
            phase = AppUpdatePhase.READY_TO_INSTALL,
            versionTag = release.versionTag,
            releaseUrl = release.releaseUrl,
            filePath = File("update.apk").absolutePath,
        ).toUpdateUiState(release)
        assertTrue(ready is UpdateUiState.ReadyToInstall)
    }

    private data class OtaFixture(
        val tag: String,
        val versionName: String,
        val versionCode: Long,
        val body: String,
        val apk: String,
        val mandatory: Boolean,
        val prerelease: Boolean,
        val draft: Boolean,
        val payload: JSONObject,
        val manifest: JSONObject,
    )

    private companion object {
        private const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        private fun fixture(
            tag: String,
            versionName: String,
            versionCode: Long,
            body: String,
            mandatory: Boolean = false,
            prerelease: Boolean = false,
            draft: Boolean = false,
        ): OtaFixture {
            val apk = "app-release.apk"
            val payload = JSONObject().apply {
                put("tag_name", tag)
                put("html_url", "https://github.com/Axygen4oO/hoplet-android/releases/tag/$tag")
                put("body", body)
                put("draft", draft)
                put("prerelease", prerelease)
                put("assets", org.json.JSONArray().put(JSONObject().apply {
                    put("name", apk)
                    put("state", "uploaded")
                    put("size", 1024)
                    put("browser_download_url", "https://github.com/Axygen4oO/hoplet-android/releases/download/$tag/$apk")
                    put("digest", "sha256:$SHA")
                }))
            }
            val manifest = JSONObject().apply {
                put("versionName", versionName)
                put("versionCode", versionCode)
                put("tag", tag)
                put("apk", apk)
                put("sha256", SHA)
                put("mandatory", mandatory)
            }
            return OtaFixture(tag, versionName, versionCode, body, apk, mandatory, prerelease, draft, payload, manifest)
        }

        private val otaFixtures = listOf(
            fixture("v1.4.9", "1.4.9", 46, "Maintenance release"),
            fixture("v1.5.0", "1.5.0", 47, "Stable update", mandatory = true),
            fixture("v1.6.0", "1.6.0", 48, "Beta update", prerelease = true),
        )
    }
}
