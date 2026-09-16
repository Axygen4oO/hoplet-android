package com.wdtt.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val TUNNEL_CHANNEL_ID = "wdtt_tunnel_v5"
    const val PUSH_GENERAL_CHANNEL_ID = "wdtt_push_general_v1"
    const val PUSH_SECURITY_CHANNEL_ID = "wdtt_push_security_v1"
    const val PUSH_SUBSCRIPTION_CHANNEL_ID = "wdtt_push_subscription_v1"
    const val PUSH_UPDATES_CHANNEL_ID = "wdtt_push_updates_v1"
    const val PUSH_GROUP_KEY = "com.wdtt.client.PUSH"
    const val PUSH_GROUP_SUMMARY_ID = Int.MIN_VALUE + 173

    fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        if (!hasPostNotificationsPermission(context)) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureTunnelChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(TUNNEL_CHANNEL_ID)
        if (existing != null && existing.importance >= NotificationManager.IMPORTANCE_DEFAULT) {
            return
        }
        val channel = NotificationChannel(
            TUNNEL_CHANNEL_ID,
            "Hoplet Tunnel",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Статус TUN-туннеля и переподключение"
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun ensurePushChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            PushChannel(PUSH_GENERAL_CHANNEL_ID, "Уведомления", "Общие уведомления Hoplet", NotificationManager.IMPORTANCE_DEFAULT, withSound = true, withVibration = true),
            PushChannel(PUSH_SECURITY_CHANNEL_ID, "Безопасность", "Важные уведомления безопасности", NotificationManager.IMPORTANCE_HIGH, withSound = true, withVibration = true),
            PushChannel(PUSH_SUBSCRIPTION_CHANNEL_ID, "Подписка", "Срок действия и состояние подписки", NotificationManager.IMPORTANCE_DEFAULT, withSound = false, withVibration = false),
            PushChannel(PUSH_UPDATES_CHANNEL_ID, "Обновления", "Обновления приложения и сервера", NotificationManager.IMPORTANCE_DEFAULT, withSound = false, withVibration = false),
        )
        channels.forEach { spec ->
            manager.createNotificationChannel(NotificationChannel(spec.id, spec.name, spec.importance).apply {
                description = spec.description
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                if (!spec.withSound) setSound(null, null)
                enableVibration(spec.withVibration)
            })
        }
    }

    private data class PushChannel(
        val id: String,
        val name: String,
        val description: String,
        val importance: Int,
        val withSound: Boolean,
        val withVibration: Boolean,
    )

    fun ensureAuxChannel(context: Context, channelId: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            channelId,
            name,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            this.description = description
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent().apply {
            when {
                Build.VERSION.SDK_INT >= 26 -> {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                else -> {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.fromParts("package", context.packageName, null)
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
