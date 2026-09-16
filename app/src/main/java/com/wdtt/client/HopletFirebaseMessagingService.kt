package com.wdtt.client

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import android.util.Log

class HopletFirebaseMessagingService : FirebaseMessagingService() {
    private companion object {
        const val TAG = "HopletFCM"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensurePushChannels(this)
    }

    override fun onNewToken(token: String) {
        scope.launch { PushRegistrationClient.register(applicationContext, token) }
    }

    override fun onDeletedMessages() {
        // FCM asks the client to reconcile after server-side message deletion.
        ServerNotificationManager.start(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.i(TAG, "FCM_MESSAGE_RECEIVED")
        Log.i(TAG, "FCM_MESSAGE_HAS_DATA=${message.data.isNotEmpty()}")
        Log.i(TAG, "FCM_MESSAGE_HAS_NOTIFICATION=${message.notification != null}")
        // Keep the receive callback alive until the local store merge finishes;
        // Firebase may tear down the service immediately after this callback.
        runBlocking(Dispatchers.IO) { processMessage(message) }
    }

    private suspend fun processMessage(message: RemoteMessage) {
        val data = message.data
        val id = data["notification_id"]?.toLongOrNull() ?: return
        if (id <= 0L) return
        val title = resolvePushTitle(message.notification?.title, data["title"])
        val body = resolvePushBody(message.notification?.body, data["message"])
        if (title.isBlank()) {
            ServerNotificationManager.start(applicationContext)
            return
        }
        val type = normalizePushType(data["type"])
        val settings = SettingsStore(applicationContext)
        if (!settings.pushEnabled.first()) return
        val allowed = when {
            type.startsWith("SECURITY") -> settings.pushSecurity.first()
			isSubscriptionReminderType(type) -> settings.pushSubscriptionReminders.first()
			isSubscriptionUpdateType(type) -> settings.pushSubscriptionUpdates.first()
            type.startsWith("UPDATE") -> settings.pushUpdates.first()
            type.startsWith("PROMOTION") -> settings.pushPromotions.first()
            else -> true
        }
        if (!allowed) return
        val deepLink = normalizePushDeepLink(data["deep_link"])
        val revision = data["revision"]?.toLongOrNull()?.takeIf { it > 0L } ?: id
        val store = ServerNotificationStore.get(applicationContext)
        val inserted = store.merge(listOf(ServerNotification(id, System.currentTimeMillis() / 1000L, title, body, type, deepLink)))
        if (!inserted) return // idempotency: polling/FCM duplicates never alert twice
        Log.i(TAG, "FCM_RENDER_START")
        Log.i(TAG, "FCM_RENDER_TITLE_PRESENT=${title.isNotBlank()}")
        Log.i(TAG, "FCM_RENDER_BODY_PRESENT=${body.isNotBlank()}")
        val rendered = showSystemNotification(id, title, body, type, deepLink, revision)
        Log.i(TAG, "FCM_RENDER_SUCCESS=$rendered")
        if (rendered) Log.i(TAG, "FCM_RENDER_SUCCESS")
        // Reconcile full server state asynchronously; push remains a transport only.
        ServerNotificationManager.start(applicationContext)
    }

    private fun showSystemNotification(id: Long, title: String, body: String, type: String, deepLink: String, revision: Long): Boolean {
        if (!NotificationHelper.areNotificationsEnabled(this)) return false
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_PUSH_NOTIFICATION_ID, id)
            putExtra(MainActivity.EXTRA_PUSH_DEEP_LINK, deepLink)
            putExtra(MainActivity.EXTRA_PUSH_TYPE, type)
            putExtra(MainActivity.EXTRA_PUSH_REVISION, revision)
            putExtra(MainActivity.EXTRA_PUSH_TITLE, title)
            putExtra(MainActivity.EXTRA_PUSH_BODY, body)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        // Data URI makes every PendingIntent identity-specific even when Android
        // reuses an existing task. Extras are still used by MainActivity so the
        // existing deep-link flow remains unchanged.
        intent.data = Uri.parse("hoplet://notifications/$id")
        val pending = PendingIntent.getActivity(this, notificationIdFor(id), intent, PUSH_PENDING_INTENT_FLAGS)
        val channel = channelForPushType(type)
        val manager = NotificationManagerCompat.from(this)
        val activePushCount = manager.activeNotifications.count {
            it.notification.group == NotificationHelper.PUSH_GROUP_KEY &&
                it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
        }
        val notificationBuilder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification_hoplet)
            .setContentTitle(title)
            .setContentText(body.takeIf { it.isNotBlank() })
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setColor(androidx.core.content.ContextCompat.getColor(this, R.color.notification_accent))
            .setPriority(if (type.startsWith("SECURITY")) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(NotificationHelper.PUSH_GROUP_KEY)
        if (shouldUseBigText(body)) {
            notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        manager.notify(notificationIdFor(id), notificationBuilder.build())
        if (activePushCount > 0) {
            val summary = NotificationCompat.Builder(this, NotificationHelper.PUSH_GENERAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_hoplet)
                .setContentTitle(getString(R.string.push_group_name))
                .setContentText(getString(R.string.push_group_summary))
                .setColor(androidx.core.content.ContextCompat.getColor(this, R.color.notification_accent))
                .setGroup(NotificationHelper.PUSH_GROUP_KEY)
                .setGroupSummary(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(NotificationHelper.PUSH_GROUP_SUMMARY_ID, summary)
        }
        return true
    }
}

internal const val BIG_TEXT_THRESHOLD = 80
internal const val PUSH_PENDING_INTENT_FLAGS: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

internal fun resolvePushTitle(notificationTitle: String?, dataTitle: String?): String =
    notificationTitle?.trim().orEmpty().ifBlank { dataTitle?.trim().orEmpty() }.ifBlank { "Hoplet" }

internal fun resolvePushBody(notificationBody: String?, dataBody: String?): String =
    notificationBody?.trim().orEmpty().ifBlank { dataBody?.trim().orEmpty() }

internal fun shouldUseBigText(body: String): Boolean = body.length >= BIG_TEXT_THRESHOLD

internal fun channelForPushType(type: String): String = when {
    type.startsWith("SECURITY") -> NotificationHelper.PUSH_SECURITY_CHANNEL_ID
    type.startsWith("SUBSCRIPTION") -> NotificationHelper.PUSH_SUBSCRIPTION_CHANNEL_ID
    type.startsWith("UPDATE") -> NotificationHelper.PUSH_UPDATES_CHANNEL_ID
    else -> NotificationHelper.PUSH_GENERAL_CHANNEL_ID
}

internal fun notificationIdFor(serverId: Long): Int {
    var value = (serverId xor (serverId ushr 32)).toInt() and Int.MAX_VALUE
    if (value == 0 || value == NotificationHelper.PUSH_GROUP_SUMMARY_ID) value = 1
    return value
}

internal val SUBSCRIPTION_REMINDER_TYPES = setOf(
	"SUBSCRIPTION_EXPIRING_7D", "SUBSCRIPTION_EXPIRING_3D", "SUBSCRIPTION_EXPIRING_1D", "SUBSCRIPTION_EXPIRED",
)
internal val SUBSCRIPTION_UPDATE_TYPES = setOf(
	"SUBSCRIPTION_ACTIVATED", "SUBSCRIPTION_RENEWED", "SUBSCRIPTION_LIMIT_CHANGED", "SUBSCRIPTION_BLOCKED", "SUBSCRIPTION_RESTORED",
)

internal fun isSubscriptionReminderType(type: String): Boolean = type == "SUBSCRIPTION" || type in SUBSCRIPTION_REMINDER_TYPES
internal fun isSubscriptionUpdateType(type: String): Boolean = type in SUBSCRIPTION_UPDATE_TYPES

internal fun normalizePushType(raw: String?): String {
	val value = raw?.trim()?.uppercase().orEmpty()
	return when {
		value in setOf("SYSTEM", "SECURITY", "SUBSCRIPTION", "PAYMENT", "UPDATE", "MAINTENANCE", "PROMOTION", "SUPPORT", "CUSTOM") -> value
		value in SUBSCRIPTION_REMINDER_TYPES || value in SUBSCRIPTION_UPDATE_TYPES -> value
		else -> "CUSTOM"
	}
}

internal fun normalizePushDeepLink(raw: String?): String {
    val candidate = raw?.trim().orEmpty()
    val value = candidate
        .substringAfter("://", candidate)
        .substringBefore('/')
        .substringBefore('?')
        .trim()
        .lowercase()
    return when (value) { "none", "" -> ""; "subscription", "support", "updates", "notifications" -> value; else -> "notifications" }
}
