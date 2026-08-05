package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerNotificationManagerTest {
    @Test
    fun `keeps only one owner entry for repeated starts from the same source`() {
        val tracker = ServerNotificationManager.PollingSessionTracker()

        tracker.activate(ServerNotificationManager.PollingOwner.APP_FOREGROUND)
        tracker.activate(ServerNotificationManager.PollingOwner.APP_FOREGROUND)

        assertEquals(1, tracker.activeOwnerCount())
    }

    @Test
    fun `keeps polling active while at least one owner remains`() {
        val tracker = ServerNotificationManager.PollingSessionTracker()

        tracker.activate(ServerNotificationManager.PollingOwner.APP_FOREGROUND)
        tracker.activate(ServerNotificationManager.PollingOwner.TUNNEL_SERVICE)
        tracker.deactivate(ServerNotificationManager.PollingOwner.APP_FOREGROUND)

        assertEquals(1, tracker.activeOwnerCount())
        assertEquals(true, tracker.hasActiveOwners())
    }

    @Test
    fun `repeated stops for the same source stay safe`() {
        val tracker = ServerNotificationManager.PollingSessionTracker()

        tracker.activate(ServerNotificationManager.PollingOwner.TUNNEL_SERVICE)
        tracker.deactivate(ServerNotificationManager.PollingOwner.TUNNEL_SERVICE)
        tracker.deactivate(ServerNotificationManager.PollingOwner.TUNNEL_SERVICE)

        assertEquals(0, tracker.activeOwnerCount())
        assertEquals(false, tracker.hasActiveOwners())
    }

    @Test
    fun `uses fast interval during first minute`() {
        val cadence = ServerNotificationManager.AdaptivePollingCadence(startedAtElapsedMs = 1_000L)

        assertEquals(
            ServerNotificationManager.PollingConfig.FAST_INTERVAL_MS,
            cadence.currentIntervalMs(nowElapsedMs = 1_000L)
        )
        assertEquals(
            ServerNotificationManager.PollingConfig.FAST_INTERVAL_MS,
            cadence.currentIntervalMs(
                nowElapsedMs = 1_000L + ServerNotificationManager.PollingConfig.FAST_PHASE_DURATION_MS - 1L
            )
        )
    }

    @Test
    fun `switches to normal interval after fast phase and to slow interval after five minutes`() {
        val cadence = ServerNotificationManager.AdaptivePollingCadence(startedAtElapsedMs = 5_000L)

        assertEquals(
            ServerNotificationManager.PollingConfig.NORMAL_INTERVAL_MS,
            cadence.currentIntervalMs(
                nowElapsedMs = 5_000L + ServerNotificationManager.PollingConfig.FAST_PHASE_DURATION_MS
            )
        )
        assertEquals(
            ServerNotificationManager.PollingConfig.NORMAL_INTERVAL_MS,
            cadence.currentIntervalMs(
                nowElapsedMs = 5_000L + ServerNotificationManager.PollingConfig.NORMAL_PHASE_DURATION_MS - 1L
            )
        )
        assertEquals(
            ServerNotificationManager.PollingConfig.SLOW_INTERVAL_MS,
            cadence.currentIntervalMs(
                nowElapsedMs = 5_000L + ServerNotificationManager.PollingConfig.NORMAL_PHASE_DURATION_MS
            )
        )
    }

    @Test
    fun `restarts fast phase after a new notification`() {
        val cadence = ServerNotificationManager.AdaptivePollingCadence(startedAtElapsedMs = 10_000L)
        val restartAt = 10_000L + ServerNotificationManager.PollingConfig.NORMAL_PHASE_DURATION_MS + 10_000L

        assertEquals(
            ServerNotificationManager.PollingConfig.SLOW_INTERVAL_MS,
            cadence.currentIntervalMs(nowElapsedMs = restartAt)
        )

        cadence.restartFastPhase(restartAt)

        assertEquals(
            ServerNotificationManager.PollingConfig.FAST_INTERVAL_MS,
            cadence.currentIntervalMs(nowElapsedMs = restartAt)
        )
        assertEquals(
            ServerNotificationManager.PollingConfig.NORMAL_INTERVAL_MS,
            cadence.currentIntervalMs(
                nowElapsedMs = restartAt + ServerNotificationManager.PollingConfig.FAST_PHASE_DURATION_MS
            )
        )
    }
}
