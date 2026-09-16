package com.wdtt.client

import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushNotificationPresentationTest {
    @Test fun `FCM notification title wins over data title`() {
        assertEquals("Заголовок FCM", resolvePushTitle(" Заголовок FCM ", "Data title"))
        assertEquals("Data title", resolvePushTitle(" ", " Data title "))
    }

    @Test fun `empty body is retained without technical placeholder`() {
        assertEquals("", resolvePushBody(" ", ""))
        assertEquals("Текст", resolvePushBody(null, " Текст "))
    }

    @Test fun `long body uses BigText threshold`() {
        assertTrue(shouldUseBigText("x".repeat(BIG_TEXT_THRESHOLD)))
        assertTrue(!shouldUseBigText("x".repeat(BIG_TEXT_THRESHOLD - 1)))
        assertTrue("перенос\nстроки".contains('\n'))
    }

    @Test fun `all notification types map to stable production channels`() {
        assertEquals(NotificationHelper.PUSH_GENERAL_CHANNEL_ID, channelForPushType("CUSTOM"))
        assertEquals(NotificationHelper.PUSH_SECURITY_CHANNEL_ID, channelForPushType("SECURITY"))
        assertEquals(NotificationHelper.PUSH_SUBSCRIPTION_CHANNEL_ID, channelForPushType("SUBSCRIPTION"))
		assertEquals(NotificationHelper.PUSH_SUBSCRIPTION_CHANNEL_ID, channelForPushType("SUBSCRIPTION_RENEWED"))
        assertEquals(NotificationHelper.PUSH_UPDATES_CHANNEL_ID, channelForPushType("UPDATE"))
        assertEquals(NotificationHelper.PUSH_GENERAL_CHANNEL_ID, channelForPushType("PROMOTION"))
    }

	@Test fun `subscription updates and reminders are separate categories`() {
		assertTrue(isSubscriptionUpdateType(normalizePushType("SUBSCRIPTION_ACTIVATED")))
		assertTrue(isSubscriptionUpdateType(normalizePushType("SUBSCRIPTION_LIMIT_CHANGED")))
		assertTrue(isSubscriptionReminderType(normalizePushType("SUBSCRIPTION_EXPIRING_7D")))
		assertTrue(isSubscriptionReminderType(normalizePushType("SUBSCRIPTION_EXPIRED")))
		assertTrue(!isSubscriptionReminderType("SUBSCRIPTION_RENEWED"))
	}

    @Test fun `deep links normalize including none`() {
        assertEquals("subscription", normalizePushDeepLink("hoplet://subscription"))
        assertEquals("updates", normalizePushDeepLink("updates/path"))
        assertEquals("", normalizePushDeepLink("hoplet://none"))
        assertEquals("notifications", normalizePushDeepLink("unknown"))
    }

    @Test fun `notification ids are stable positive and distinct`() {
        assertTrue(notificationIdFor(42L) > 0)
        assertNotEquals(notificationIdFor(42L), notificationIdFor(43L))
        assertNotEquals(NotificationHelper.PUSH_GROUP_SUMMARY_ID, notificationIdFor(42L))
    }

    @Test fun `pending intent uses immutable update current`() {
        assertTrue(PUSH_PENDING_INTENT_FLAGS and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(PUSH_PENDING_INTENT_FLAGS and PendingIntent.FLAG_UPDATE_CURRENT != 0)
    }

    @Test fun `group key is shared and summary id is reserved`() {
        assertEquals("com.wdtt.client.PUSH", NotificationHelper.PUSH_GROUP_KEY)
        assertTrue(NotificationHelper.PUSH_GROUP_SUMMARY_ID < 0)
    }

    @Test fun `duplicate delivery is suppressed even with empty body`() {
        val item = ServerNotification(7L, 1L, "Событие", "")
        val first = ServerNotificationReducer.merge(ServerNotificationState(), listOf(item))
        val second = ServerNotificationReducer.merge(first, listOf(item))
        assertEquals(first, second)
        assertEquals(1, second.notifications.size)
    }
}
