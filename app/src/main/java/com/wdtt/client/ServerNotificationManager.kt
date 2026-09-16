package com.wdtt.client

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
import java.net.URL

object ServerNotificationManager {
    private const val TAG = "ServerNotifications"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    internal enum class PollingOwner { APP_FOREGROUND, TUNNEL_SERVICE }

    internal object PollingConfig {
        const val FAST_INTERVAL_MS = 5_000L
        const val NORMAL_INTERVAL_MS = 30_000L
        const val SLOW_INTERVAL_MS = 2 * 60_000L
        const val FAST_PHASE_DURATION_MS = 60_000L
        const val NORMAL_PHASE_DURATION_MS = 5 * 60_000L
    }

    internal class AdaptivePollingCadence(startedAtElapsedMs: Long) {
        private var phaseStartedAtElapsedMs = startedAtElapsedMs
        fun currentIntervalMs(nowElapsedMs: Long): Long = when {
            (nowElapsedMs - phaseStartedAtElapsedMs).coerceAtLeast(0L) < PollingConfig.FAST_PHASE_DURATION_MS -> PollingConfig.FAST_INTERVAL_MS
            (nowElapsedMs - phaseStartedAtElapsedMs).coerceAtLeast(0L) < PollingConfig.NORMAL_PHASE_DURATION_MS -> PollingConfig.NORMAL_INTERVAL_MS
            else -> PollingConfig.SLOW_INTERVAL_MS
        }
        fun restartFastPhase(nowElapsedMs: Long) { phaseStartedAtElapsedMs = nowElapsedMs }
    }

    internal class PollingSessionTracker {
        private val activeOwners = linkedSetOf<PollingOwner>()
        fun activate(owner: PollingOwner) { activeOwners.add(owner) }
        fun deactivate(owner: PollingOwner) { activeOwners.remove(owner) }
        fun hasActiveOwners(): Boolean = activeOwners.isNotEmpty()
        fun activeOwnerCount(): Int = activeOwners.size
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val sessionTracker = PollingSessionTracker()
    @Volatile private var pollJob: Job? = null

    private data class FetchResult(
        val notifications: List<ServerNotification> = emptyList(),
        val statusCode: Int? = null,
        val error: String? = null,
    )

    internal enum class CredentialSource { JWT, PASSWORD }
    private data class Credential(val value: String, val source: CredentialSource)

    /**
     * Notifications are authorized by a user/admin JWT or by the connection
     * password stored in the selected profile.  Depending on the profile,
     * that password is either a generated subscription password or the server
     * owner's MainPassword.  Deploy SSH credentials are deliberately excluded.
     */
    internal fun selectPrimaryCredential(jwt: String, password: String): CredentialSource? = when {
        jwt.isNotBlank() -> CredentialSource.JWT
        password.isNotBlank() -> CredentialSource.PASSWORD
        else -> null
    }

    internal fun shouldRetryWithPassword(
        primary: CredentialSource?,
        statusCode: Int?,
        passwordAvailable: Boolean,
        passwordDiffers: Boolean,
    ): Boolean = primary == CredentialSource.JWT &&
        statusCode in setOf(401, 403) && passwordAvailable && passwordDiffers

    internal fun shouldRetryWithPassword(
        jwtUsed: Boolean,
        statusCode: Int?,
        passwordAvailable: Boolean,
        passwordDiffers: Boolean,
    ): Boolean = shouldRetryWithPassword(
        if (jwtUsed) CredentialSource.JWT else CredentialSource.PASSWORD,
        statusCode,
        passwordAvailable,
        passwordDiffers,
    )

    internal fun isSuccessfulResponse(statusCode: Int?, error: String?): Boolean =
        statusCode != null && statusCode in 200..299 && error == null

    fun start(context: Context) = start(context, PollingOwner.APP_FOREGROUND)
    internal fun startForTunnel(context: Context) = start(context, PollingOwner.TUNNEL_SERVICE)

    private fun start(context: Context, owner: PollingOwner) = synchronized(lock) {
        sessionTracker.activate(owner)
        if (pollJob?.isActive != true && sessionTracker.hasActiveOwners()) {
            val appContext = context.applicationContext
            pollJob = scope.launch { pollLoop(appContext) }
        }
    }

    fun stop() = stop(PollingOwner.APP_FOREGROUND)
    internal fun stopForTunnel() = stop(PollingOwner.TUNNEL_SERVICE)

    private fun stop(owner: PollingOwner) {
        val job = synchronized(lock) {
            sessionTracker.deactivate(owner)
            if (sessionTracker.hasActiveOwners()) null else pollJob.also { pollJob = null }
        }
        job?.cancel()
    }

    private suspend fun pollLoop(context: Context) {
        val settings = SettingsStore(context)
        val store = ServerNotificationStore.get(context)
        store.migrateLegacyLatestId(settings.getLastServerNotificationId())
        val cadence = AdaptivePollingCadence(SystemClock.elapsedRealtime())
        while (currentCoroutineContext().isActive) {
            val received = runCatching { checkOnce(context, settings, store) }
                .onFailure { Log.w(TAG, "Notification polling failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            if (received) cadence.restartFastPhase(SystemClock.elapsedRealtime())
            delay(cadence.currentIntervalMs(SystemClock.elapsedRealtime()))
        }
    }

    private suspend fun checkOnce(context: Context, settings: SettingsStore, store: ServerNotificationStore): Boolean {
        val endpoint = resolveServerEndpoint(settings)
        if (endpoint == null) {
            Log.w(TAG, "Notification server unavailable: endpoint is not configured")
            return false
        }
        // Use the same JWT as the other authenticated API clients.  The
        // connection password remains a compatibility fallback for older
        // servers that authorize bearer passwords.
        val jwt = AdminSession.getToken(context).orEmpty().trim()
        val password = settings.connectionPassword.first().trim()
        val primarySource = selectPrimaryCredential(jwt, password)
        val primary = primarySource?.let { source ->
            Credential(if (source == CredentialSource.JWT) jwt else password, source)
        }
        if (primary == null) {
            Log.w(TAG, "Notification authentication failed: no credentials available")
            return false
        }
        // The history contains at most five records, so every successful poll
        // uses it as an authoritative snapshot. The latest/after endpoint is
        // intentionally kept server-side for compatibility, but a delta alone
        // cannot describe records deleted by an administrator.
        val path = "/api/notifications"
        var result = fetchNotifications(endpoint, path, primary.value)
        val retry = shouldRetryWithPassword(primary.source, result.statusCode, password.isNotBlank(), password != primary.value)
        if (retry) {
            result = fetchNotifications(endpoint, path, password)
        }
        if (!isSuccessfulResponse(result.statusCode, result.error)) {
            when {
                result.error == "NETWORK_ERROR" -> Log.w(TAG, "Notification network error")
                result.error == "JSON_ERROR" -> Log.w(TAG, "Notification response JSON parsing failed")
                result.statusCode == 401 || result.statusCode == 403 ->
                    Log.w(TAG, "Notification authentication failed: HTTP ${result.statusCode}")
                result.statusCode != null && result.statusCode in 500..599 ->
                    Log.w(TAG, "Notification server unavailable: HTTP ${result.statusCode}")
                result.statusCode != null -> Log.w(TAG, "Notification HTTP error: ${result.statusCode}")
                else -> Log.w(TAG, "Notification request failed")
            }
            return false
        }
        val incoming = result.notifications
        val changed = store.replaceFromServer(incoming)
        settings.saveLastServerNotificationId(store.state.value.latestServerId)
        return changed
    }

    private suspend fun resolveServerEndpoint(settings: SettingsStore): String? {
        val peer = settings.peer.first().trim()
        if (peer.isEmpty()) return null
        val port = if (settings.manualPortsEnabled.first()) settings.serverDtlsPort.first() else 56000
        return PeerAddress.httpEndpoint(peer, port)
    }

    private suspend fun fetchNotifications(endpoint: String, path: String, credential: String): FetchResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("http://$endpoint$path").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $credential")
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val category = when (responseCode) {
                    401 -> "AUTH_UNAUTHORIZED"
                    403 -> "AUTH_FORBIDDEN"
                    404 -> "ENDPOINT_NOT_FOUND"
                    429 -> "RATE_LIMITED"
                    in 500..599 -> "SERVER_ERROR"
                    else -> "HTTP_ERROR"
                }
                return@withContext FetchResult(statusCode = responseCode, error = "HTTP $responseCode ($category)")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val parsed = parseNotificationsResult(body)
            if (parsed.isFailure) return@withContext FetchResult(statusCode = responseCode, error = "JSON_ERROR")
            FetchResult(notifications = parsed.getOrThrow(), statusCode = responseCode)
        } catch (_: IOException) {
            FetchResult(error = "NETWORK_ERROR")
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parseNotificationsResult(body: String): Result<List<ServerNotification>> = runCatching {
        val array = JSONObject(body).getJSONArray("notifications")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val notification = ServerNotification(
                    id = item.optLong("id"),
                    createdAt = item.optLong("created_at"),
                    title = item.optString("title").trim(),
                    message = item.optString("message").trim(),
                    type = item.optString("type", "CUSTOM"),
                    deepLink = item.optString("deep_link"),
                )
                if (notification.id > 0 && notification.title.isNotBlank()) add(notification)
            }
        }
    }

    internal fun parseNotifications(body: String): List<ServerNotification> = parseNotificationsResult(body).getOrDefault(emptyList())

}
