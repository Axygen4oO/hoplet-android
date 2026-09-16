package com.wdtt.client

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Состояния, которые можно безопасно показывать пользователю. */
enum class SubscriptionDisplayState {
    ACTIVE,
    EXPIRING,
    EXPIRED,
    BLOCKED,
    MISSING,
}

/** Последнее подтверждённое состояние подписки без секретов и технических идентификаторов. */
data class CachedSubscriptionStatus(
    val maxDevices: Int,
    val boundDevices: Int,
    val activeDevices: Int,
    val isCurrentBound: Boolean,
    val expiresAt: Long,
    val subscriptionStatus: String,
    val plan: String,
    val isMainPassword: Boolean,
    val savedAt: Long,
)

fun subscriptionDisplayState(
    status: String,
    expiresAtSeconds: Long,
    nowSeconds: Long,
    isMainPassword: Boolean = false,
): SubscriptionDisplayState {
    if (isMainPassword) return SubscriptionDisplayState.MISSING

    return when {
        status.equals("blocked", ignoreCase = true) -> SubscriptionDisplayState.BLOCKED
        status.equals("expired", ignoreCase = true) -> SubscriptionDisplayState.EXPIRED
        expiresAtSeconds > 0L && expiresAtSeconds <= nowSeconds -> SubscriptionDisplayState.EXPIRED
        expiresAtSeconds > 0L && expiresAtSeconds - nowSeconds <= 5L * 24L * 60L * 60L ->
            SubscriptionDisplayState.EXPIRING
        else -> SubscriptionDisplayState.ACTIVE
    }
}

fun remainingSubscriptionText(daysLeft: Int): String {
    val days = daysLeft.coerceAtLeast(0)
    return when {
        days == 1 -> "Остался 1 день"
        days % 10 == 1 && days % 100 != 11 -> "Осталось $days день"
        days % 10 in 2..4 && days % 100 !in 12..14 -> "Осталось $days дня"
        else -> "Осталось $days дней"
    }
}

fun subscriptionDaysLeft(expiresAtSeconds: Long, nowSeconds: Long): Int {
    if (expiresAtSeconds <= 0L || expiresAtSeconds <= nowSeconds) return 0
    return kotlin.math.ceil((expiresAtSeconds - nowSeconds) / 86400.0).toInt().coerceAtLeast(1)
}

/** Форматирует дату в timezone устройства, без UTC/system-timezone смешения. */
fun subscriptionExpiryText(expiresAtSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (expiresAtSeconds <= 0L) return "Срок действия не указан"

    val expiry = Calendar.getInstance().apply { timeInMillis = expiresAtSeconds * 1000L }
    val today = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(expiresAtSeconds * 1000L))
    val dayMonth = SimpleDateFormat("d MMMM", Locale("ru")).format(Date(expiresAtSeconds * 1000L))

    return when {
        isSameDay(expiry, today) -> "Истекает в $time"
        isSameDay(expiry, tomorrow) -> "Истекает завтра, $dayMonth в $time"
        else -> "Истекает $dayMonth в $time"
    }
}

fun subscriptionExpiredText(expiresAtSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (expiresAtSeconds <= 0L) return "Срок действия закончился"

    val dayMonth = SimpleDateFormat("d MMMM", Locale("ru")).format(Date(expiresAtSeconds * 1000L))
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(expiresAtSeconds * 1000L))
    return "Истекла $dayMonth в $time"
}

private fun isSameDay(first: Calendar, second: Calendar): Boolean =
    first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
        first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
