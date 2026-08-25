package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRuntimeTest {
    @Test
    fun encodesAndDecodesSnapshotWithoutLosingFields() {
        val snapshot = AppUpdateDownloadSnapshot(
            phase = AppUpdatePhase.WAITING_FOR_NETWORK,
            versionTag = "1.4.4",
            releaseUrl = "https://example.test/releases/v1.4.4",
            downloadUrl = "https://example.test/hoplet.apk",
            releaseNotes = "Important fixes",
            isPrerelease = true,
            downloadFileName = "hoplet.apk",
            downloadSizeBytes = 42_000_000L,
            expectedSha256 = "abc",
            sha256AssetUrl = "https://example.test/hoplet.apk.sha256",
            filePath = "C:/updates/hoplet.apk",
            tempFilePath = "C:/updates/hoplet.part",
            downloadedBytes = 12_345L,
            totalBytes = 67_890L,
            speedBytesPerSecond = 456L,
            estimatedRemainingMs = 789L,
            startedAt = 111L,
            updatedAt = 222L,
            lastError = "network lost",
            lastVerifiedSha256 = "def",
            statusMessage = "Waiting for network",
            rangeSupported = true,
            autoResumeOnNetwork = true,
        )

        val decoded = decodeAppUpdateSnapshot(encodeAppUpdateSnapshot(snapshot))

        assertEquals(snapshot, decoded)
        assertTrue(decoded.matchesVersion("v1.4.4"))
    }

    @Test
    fun fallsBackToIdleSnapshotWhenStoredStateIsInvalid() {
        val decoded = decodeAppUpdateSnapshot("{not-json")

        assertEquals(AppUpdatePhase.IDLE, decoded.phase)
        assertTrue(decoded.versionTag.isBlank())
        assertFalse(decoded.autoResumeOnNetwork)
    }
}
