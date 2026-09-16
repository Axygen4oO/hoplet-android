package com.wdtt.client

import org.junit.Assert.assertEquals
	import org.junit.Assert.assertFalse
	import org.junit.Assert.assertTrue
import org.junit.Test

class ServerNotificationManagerTest {
	private fun notification(id: Long) = ServerNotification(id, id * 10, "Title $id", "Message $id")

	@Test
	fun `parses multiple notification records`() {
		val parsed = ServerNotificationManager.parseNotifications("""{"notifications":[{"id":12,"created_at":120,"title":"T12","message":"M12"},{"id":11,"created_at":110,"title":"T11","message":"M11"}]}""")
		assertEquals(listOf(12L, 11L), parsed.map { it.id })
	}

	@Test
	fun `reports malformed server json instead of treating it as empty response`() {
		assertTrue(ServerNotificationManager.parseNotificationsResult("not-json").isFailure)
		assertTrue(ServerNotificationManager.parseNotificationsResult("{}").isFailure)
	}

	@Test
	fun `merge orders deduplicates and keeps five`() {
		val state = ServerNotificationReducer.merge(ServerNotificationState(), (1L..7L).map(::notification) + notification(7))
		assertEquals(listOf(7L, 6L, 5L, 4L, 3L), state.notifications.map { it.id })
		assertEquals(7L, state.latestServerId)
		assertEquals(5, state.unreadCount)
	}

	@Test
	fun `mark read preserves history and new arrival restores badge`() {
		val initial = ServerNotificationReducer.merge(ServerNotificationState(), listOf(notification(1), notification(2), notification(3)))
		val read = ServerNotificationReducer.markAllRead(initial)
		assertEquals(0, read.unreadCount)
		assertEquals(3, read.notifications.size)
		val next = ServerNotificationReducer.merge(read, listOf(notification(4)))
		assertEquals(1, next.unreadCount)
		assertTrue(4L !in next.readIds)
	}

	@Test
	fun `empty incoming history does not change state`() {
		val state = ServerNotificationState()
		assertEquals(state, ServerNotificationReducer.merge(state, emptyList()))
		assertFalse(state.unreadCount > 0)
	}

    @Test
    fun `authoritative snapshot replaces local store`() {
        val local = ServerNotificationReducer.merge(
            ServerNotificationState(),
            listOf(notification(99), notification(3), notification(2)),
        )

        val synced = ServerNotificationReducer.replaceFromServer(
            local,
            listOf(notification(3), notification(2)),
        )

        assertEquals(listOf(3L, 2L), synced.notifications.map { it.id })
        assertEquals(3L, synced.latestServerId)
    }

    @Test
    fun `deleted only server notification disappears locally`() {
        val local = ServerNotificationReducer.merge(ServerNotificationState(), listOf(notification(26)))

        val synced = ServerNotificationReducer.replaceFromServer(local, emptyList())

        assertTrue(synced.notifications.isEmpty())
        assertEquals(0L, synced.latestServerId)
        assertEquals(0, synced.unreadCount)
    }

    @Test
    fun `deleted middle server notification disappears locally`() {
        val local = ServerNotificationReducer.merge(
            ServerNotificationState(),
            listOf(notification(27), notification(26), notification(25)),
        )

        val synced = ServerNotificationReducer.replaceFromServer(
            local,
            listOf(notification(27), notification(25)),
        )

        assertEquals(listOf(27L, 25L), synced.notifications.map { it.id })
    }

    @Test
    fun `deleted latest server notification recalculates cursor`() {
        val local = ServerNotificationReducer.merge(
            ServerNotificationState(),
            listOf(notification(27), notification(26), notification(25)),
        )

        val synced = ServerNotificationReducer.replaceFromServer(
            local,
            listOf(notification(26), notification(25)),
        )

        assertEquals(listOf(26L, 25L), synced.notifications.map { it.id })
        assertEquals(26L, synced.latestServerId)
    }

    @Test
    fun `offline response is rejected and local cache is preserved`() {
        val cached = ServerNotificationReducer.merge(
            ServerNotificationState(),
            listOf(notification(26)),
        )

        val successful = ServerNotificationManager.isSuccessfulResponse(null, "NETWORK_ERROR")
        val afterPoll = if (successful) {
            ServerNotificationReducer.replaceFromServer(cached, emptyList())
        } else {
            cached
        }

        assertFalse(successful)
        assertEquals(cached, afterPoll)
    }

    @Test
    fun `read status is preserved for records still on server`() {
        val read = ServerNotificationReducer.markAllRead(
            ServerNotificationReducer.merge(ServerNotificationState(), listOf(notification(26))),
        )

        val synced = ServerNotificationReducer.replaceFromServer(read, listOf(notification(26)))

        assertEquals(setOf(26L), synced.readIds)
        assertEquals(0, synced.unreadCount)
    }

    @Test
    fun `new record stays unread after authoritative sync`() {
        val read = ServerNotificationReducer.markAllRead(
            ServerNotificationReducer.merge(ServerNotificationState(), listOf(notification(26))),
        )

        val synced = ServerNotificationReducer.replaceFromServer(
            read,
            listOf(notification(27), notification(26)),
        )

        assertEquals(setOf(26L), synced.readIds)
        assertEquals(1, synced.unreadCount)
        assertTrue(27L !in synced.readIds)
    }

    @Test
    fun `read ids for deleted records are cleaned`() {
        val read = ServerNotificationReducer.markAllRead(
            ServerNotificationReducer.merge(
                ServerNotificationState(),
                listOf(notification(27), notification(26), notification(25)),
            ),
        )

        val synced = ServerNotificationReducer.replaceFromServer(
            read,
            listOf(notification(27), notification(25)),
        )

        assertEquals(setOf(27L, 25L), synced.readIds)
        assertTrue(26L !in synced.readIds)
    }

    @Test
    fun `authoritative snapshot retains at most five newest records`() {
        val synced = ServerNotificationReducer.replaceFromServer(
            ServerNotificationState(),
            (1L..7L).map(::notification),
        )

        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), synced.notifications.map { it.id })
        assertEquals(5, synced.unreadCount)
    }

	@Test
	fun `cache codec persists records read ids and latest id`() {
		val state = ServerNotificationReducer.markAllRead(ServerNotificationReducer.merge(ServerNotificationState(), listOf(notification(8), notification(9))))
		val restored = ServerNotificationCacheCodec.decode(ServerNotificationCacheCodec.encode(state))
		assertEquals(state, restored)
	}

    @Test
    fun `id 19 arrives when local cursor is 18`() {
        val state = ServerNotificationReducer.merge(
            ServerNotificationState(latestServerId = 18L), listOf(notification(19L))
        )
        assertEquals(19L, state.latestServerId)
        assertEquals(listOf(19L), state.notifications.map { it.id })
        assertEquals(1, state.unreadCount)
    }

    @Test
    fun `id 19 already acknowledged by cursor is not duplicated`() {
        val state = ServerNotificationReducer.mergeHistory(
            ServerNotificationState(latestServerId = 19L), listOf(notification(19L))
        )
        assertEquals(19L, state.latestServerId)
        assertEquals(listOf(19L), state.notifications.map { it.id })
        assertEquals(0, state.unreadCount)
    }

    @Test
    fun `empty local state accepts id 19`() {
        val state = ServerNotificationReducer.mergeHistory(ServerNotificationState(), listOf(notification(19L)))
        assertEquals(19L, state.latestServerId)
        assertEquals(1, state.unreadCount)
    }

    @Test
    fun `password retry only follows jwt 401 or 403`() {
        assertTrue(ServerNotificationManager.shouldRetryWithPassword(true, 401, true, true))
        assertTrue(ServerNotificationManager.shouldRetryWithPassword(true, 403, true, true))
        assertFalse(ServerNotificationManager.shouldRetryWithPassword(true, 200, true, true))
        assertFalse(ServerNotificationManager.shouldRetryWithPassword(true, 404, true, true))
        assertFalse(ServerNotificationManager.shouldRetryWithPassword(true, 429, true, true))
        assertFalse(ServerNotificationManager.shouldRetryWithPassword(true, 500, true, true))
        assertFalse(ServerNotificationManager.shouldRetryWithPassword(false, 401, true, true))
        assertFalse(ServerNotificationManager.shouldRetryWithPassword(true, 401, false, true))
    }

    @Test
    fun `selects jwt before profile password and password when jwt is missing`() {
        assertEquals(
            ServerNotificationManager.CredentialSource.JWT,
            ServerNotificationManager.selectPrimaryCredential("jwt", "profile-pass")
        )
        assertEquals(
            ServerNotificationManager.CredentialSource.PASSWORD,
            ServerNotificationManager.selectPrimaryCredential("", "profile-pass")
        )
        assertEquals(null, ServerNotificationManager.selectPrimaryCredential("", ""))
    }

    @Test
    fun `jwt auth failure retries with a different profile password`() {
        assertTrue(
            ServerNotificationManager.shouldRetryWithPassword(
                ServerNotificationManager.CredentialSource.JWT,
                401,
                passwordAvailable = true,
                passwordDiffers = true,
            )
        )
        assertFalse(
            ServerNotificationManager.shouldRetryWithPassword(
                ServerNotificationManager.CredentialSource.PASSWORD,
                401,
                passwordAvailable = true,
                passwordDiffers = true,
            )
        )
    }

    @Test
    fun `unauthorized response is not treated as a successful empty list`() {
        assertFalse(ServerNotificationManager.isSuccessfulResponse(401, "HTTP 401"))
        assertFalse(ServerNotificationManager.isSuccessfulResponse(403, "HTTP 403"))
        assertFalse(ServerNotificationManager.isSuccessfulResponse(null, "NETWORK_ERROR"))
        assertTrue(ServerNotificationManager.isSuccessfulResponse(200, null))
    }
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
