package com.wdtt.client

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private data class ServerNotification(
    val id: Long,
    val title: String,
    val message: String,
    val createdAt: Long,
)

object ServerNotificationManager {
    private const val TAG = "ServerNotify"
    private const val POLL_INTERVAL_MS = 300000L
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000
    private const val NOTIFICATION_ID = 41051

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile
    private var pollJob: Job? = null

    fun start(context: Context) {
        synchronized(lock) {
            if (pollJob?.isActive == true) return
            val appContext = context.applicationContext
            pollJob = scope.launch {
                pollLoop(appContext)
            }
        }
    }

    fun stop() {
        val jobToCancel = synchronized(lock) {
            val existing = pollJob
            pollJob = null
            existing
        }
        jobToCancel?.cancel()
    }

    private suspend fun pollLoop(context: Context) {
        val settingsStore = SettingsStore(context)
        while (currentCoroutineContext().isActive) {
            runCatching {
                checkOnce(context, settingsStore)
            }.onFailure { error ->
                Log.w(TAG, "Notification polling failed: ${error.message}", error)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun checkOnce(context: Context, settingsStore: SettingsStore) {
        val serverEndpoint = resolveServerEndpoint(settingsStore) ?: return
        val lastNotificationId = settingsStore.getLastServerNotificationId()
        val notification = fetchLatestNotification(serverEndpoint, lastNotificationId) ?: return
        if (notification.id <= lastNotificationId) return

        if (!showNotification(context, notification)) {
            return
        }

        settingsStore.saveLastServerNotificationId(notification.id)
    }

    private suspend fun resolveServerEndpoint(settingsStore: SettingsStore): String? {
        val peer = settingsStore.peer.first().trim()
        if (peer.isEmpty()) return null

        val defaultPort = if (settingsStore.manualPortsEnabled.first()) {
            settingsStore.serverDtlsPort.first()
        } else {
            56000
        }

        return PeerAddress.httpEndpoint(peer, defaultPort)
    }

    private suspend fun fetchLatestNotification(serverEndpoint: String, afterId: Long): ServerNotification? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            val requestUrl = "http://$serverEndpoint/api/notifications/latest?after=$afterId"

            try {
                conn = URL(requestUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
                    return@withContext null
                }

                val responseBody = try {
                    val responseStream = if (responseCode in 200..299) {
                        conn.inputStream
                    } else {
                        conn.errorStream ?: conn.inputStream
                    }
                    responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed reading /api/notifications/latest response", e)
                    ""
                }

                if (responseCode !in 200..299 || responseBody.isBlank()) {
                    return@withContext null
                }

                val json = JSONObject(responseBody)
                val id = json.optLong("id", 0L)
                if (id <= 0L) return@withContext null

                ServerNotification(
                    id = id,
                    title = json.optString("title").trim(),
                    message = json.optString("message").trim(),
                    createdAt = json.optLong("created_at", 0L),
                )
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Notification polling timed out for url=$requestUrl", e)
                null
            } catch (e: UnknownHostException) {
                Log.w(TAG, "Notification polling unknown host for url=$requestUrl", e)
                null
            } catch (e: SSLException) {
                Log.w(TAG, "Notification polling SSL failure for url=$requestUrl", e)
                null
            } catch (e: IOException) {
                Log.w(TAG, "Notification polling IO failure for url=$requestUrl", e)
                null
            } catch (e: Exception) {
                Log.w(TAG, "Notification polling unexpected failure for url=$requestUrl", e)
                null
            } finally {
                conn?.disconnect()
            }
        }

    @SuppressLint("MissingPermission")
    private fun showNotification(context: Context, notification: ServerNotification): Boolean {
        if (!NotificationHelper.areNotificationsEnabled(context)) {
            return false
        }

        NotificationHelper.ensureServerNotificationsChannel(context)

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val title = notification.title.ifBlank { "Уведомление" }
        val message = notification.message.ifBlank { "Новое уведомление от сервера." }

        val builder = NotificationCompat.Builder(
            context,
            NotificationHelper.SERVER_NOTIFICATIONS_CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        Log.i(TAG, "Displayed server notification id=${notification.id} createdAt=${notification.createdAt}")
        return true
    }
}
