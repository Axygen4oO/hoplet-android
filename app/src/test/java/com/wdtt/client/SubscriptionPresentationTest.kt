package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SubscriptionPresentationTest {
    @Test
    fun `active and expiring states use server expiry`() {
        val now = 1_000_000L
        assertEquals(
            SubscriptionDisplayState.ACTIVE,
            subscriptionDisplayState("active", now + 10 * 86400L, now)
        )
        assertEquals(
            SubscriptionDisplayState.EXPIRING,
            subscriptionDisplayState("active", now + 2 * 86400L, now)
        )
    }

    @Test
    fun `expired blocked and main password do not look active`() {
        val now = 1_000_000L
        assertEquals(SubscriptionDisplayState.EXPIRED, subscriptionDisplayState("expired", now + 1000, now))
        assertEquals(SubscriptionDisplayState.EXPIRED, subscriptionDisplayState("active", now - 1, now))
        assertEquals(SubscriptionDisplayState.BLOCKED, subscriptionDisplayState("blocked", now + 1000, now))
        assertEquals(SubscriptionDisplayState.MISSING, subscriptionDisplayState("active", 0, now, isMainPassword = true))
    }

    @Test
    fun `remaining days have correct russian endings and no negatives`() {
        assertEquals("Остался 1 день", remainingSubscriptionText(1))
        assertEquals("Осталось 191 день", remainingSubscriptionText(191))
        assertEquals("Осталось 2 дня", remainingSubscriptionText(2))
        assertEquals("Осталось 5 дней", remainingSubscriptionText(5))
        assertEquals("Осталось 0 дней", remainingSubscriptionText(-10))
        assertEquals(1, subscriptionDaysLeft(1000L + 1, 1000L))
    }

    @Test
    fun `expiry formatting follows device local calendar`() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tomorrow = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
        }
        val result = subscriptionExpiryText(tomorrow.timeInMillis / 1000L, now.timeInMillis)
        assertTrue(result.startsWith("Истекает завтра"))
    }

    @Test
    fun `expired formatting keeps date and time`() {
        val expiry = Calendar.getInstance().apply {
            set(Calendar.MONTH, Calendar.MARCH)
            set(Calendar.DAY_OF_MONTH, 25)
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 40)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val result = subscriptionExpiredText(expiry.timeInMillis / 1000L)
        assertTrue(result.startsWith("Истекла 25 марта в 20:40"))
    }
}
