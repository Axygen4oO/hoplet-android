package com.wdtt.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test
	import java.util.Calendar

class NotificationCenterDialogTest {
    @Test
    fun `badge labels zero one nine and overflow`() {
        assertEquals("0", notificationBadgeLabel(0))
        assertEquals("1", notificationBadgeLabel(1))
        assertEquals("9", notificationBadgeLabel(9))
        assertEquals("9+", notificationBadgeLabel(10))
    }

	@Test
	fun `formats fresh and older dates in device local time`() {
		val now = Calendar.getInstance().apply {
			set(2026, Calendar.SEPTEMBER, 14, 15, 0, 0)
			set(Calendar.MILLISECOND, 0)
		}.timeInMillis
		val today = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 14); set(Calendar.MINUTE, 32) }.timeInMillis / 1000
		val older = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 5) }.timeInMillis / 1000
		assertEquals("сегодня, 14:32", formatNotificationTime(today, now))
		assertEquals("13 сентября, 21:05", formatNotificationTime(older, now))
	}
}
