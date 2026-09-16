package com.wdtt.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wdtt.client.ServerNotification
import com.wdtt.client.ServerNotificationState
import java.text.SimpleDateFormat
import java.util.Date
	import java.util.Calendar
import java.util.Locale

internal fun notificationBadgeLabel(unreadCount: Int): String = if (unreadCount > 9) "9+" else unreadCount.coerceAtLeast(0).toString()

@Composable
fun NotificationCenterDialog(
    state: ServerNotificationState,
    initiallyUnreadIds: Set<Long>,
    onDismiss: () -> Unit,
) {
    HopletAlertDialog(
        onDismissRequest = onDismiss,
        surfaceModifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        title = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Уведомления", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }
        },
        text = {
            if (state.notifications.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Уведомлений пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.notifications.take(5).forEach { item ->
                        NotificationCard(item, item.id in initiallyUnreadIds)
                    }
                }
            }
        },
    )
}

@Composable
private fun NotificationCard(notification: ServerNotification, unread: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (unread) accent.copy(alpha = 0.13f) else HopletModalDefaults.softContainerColor(),
        border = BorderStroke(1.dp, if (unread) accent.copy(alpha = 0.48f) else HopletModalDefaults.borderColor().copy(alpha = 0.72f)),
        tonalElevation = 0.dp,
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (unread) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(notification.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(formatNotificationTime(notification.createdAt), style = MaterialTheme.typography.labelSmall, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(notification.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

internal fun formatNotificationTime(timestampSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (timestampSeconds <= 0L) return "Время неизвестно"
    val millis = timestampSeconds * 1000L
    val locale = Locale("ru", "RU")
	val notificationDay = Calendar.getInstance().apply { timeInMillis = millis }
	val currentDay = Calendar.getInstance().apply { timeInMillis = nowMillis }
	val isToday = notificationDay.get(Calendar.ERA) == currentDay.get(Calendar.ERA) &&
		notificationDay.get(Calendar.YEAR) == currentDay.get(Calendar.YEAR) &&
		notificationDay.get(Calendar.DAY_OF_YEAR) == currentDay.get(Calendar.DAY_OF_YEAR)
	return if (isToday) {
        "сегодня, ${SimpleDateFormat("HH:mm", locale).format(Date(millis))}"
    } else {
        SimpleDateFormat("d MMMM, HH:mm", locale).format(Date(millis))
    }
}
