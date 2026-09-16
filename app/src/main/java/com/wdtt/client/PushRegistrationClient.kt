package com.wdtt.client

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Registers this installation against the imported subscription. */
object PushRegistrationClient {
    private const val PREFS = "push_registration"
    private const val INSTALLATION_ID = "installation_id"
    private const val PENDING_TOKEN = "pending_token"
    private const val REGISTERED_TOKEN_HASH = "registered_token_hash"
    private const val REGISTERED_IDENTITY_HASH = "registered_identity_hash"
    private const val TAG = "PushRegistration"
    private const val RETRY_DELAY_MS = 30_000L
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = PushRegistrationLifecycle()
    private val operationMutex = Mutex()
    @Volatile private var retryJob: Job? = null

    private fun diag(message: String) = Log.i(TAG, message)
    private fun safeError(error: Throwable): String = "${error::class.java.simpleName}:${error.message.orEmpty().take(120)}"
    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun installationId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(INSTALLATION_ID, null)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(INSTALLATION_ID, it).apply()
        }

    private fun deviceId(context: Context): String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"

    private suspend fun identity(context: Context): Triple<String, String, String>? {
        val settings = SettingsStore(context)
        val peer = settings.peer.first().trim()
        val password = settings.connectionPassword.first().trim()
        if (peer.isBlank() || password.isBlank()) return null
        return Triple(peer, password, deviceId(context))
    }

    suspend fun register(context: Context, token: String) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val appContext = context.applicationContext
            if (token.isBlank()) return@withLock
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PENDING_TOKEN, token).apply()
            diag("TOKEN_READY=true")
            val (peer, subscriptionPassword, androidDeviceId) = identity(appContext) ?: run {
                diag("SUBSCRIPTION_READY=false"); diag("REGISTRATION_READY=false"); return@withLock
            }
            diag("SUBSCRIPTION_READY=true")
            val settings = SettingsStore(appContext)
            val port = if (settings.manualPortsEnabled.first()) settings.serverDtlsPort.first() else 56000
            val enabled = settings.pushEnabled.first() && NotificationHelper.areNotificationsEnabled(appContext)
            val endpoint = PeerAddress.httpEndpoint(peer, port)
            val url = "http://$endpoint/api/push/register"
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val tokenHash = fingerprint(token)
            val identityHash = fingerprint(subscriptionPassword + "\u0000" + androidDeviceId)
            val decision = lifecycle.begin(tokenHash, identityHash, prefs.getString(REGISTERED_TOKEN_HASH, null), prefs.getString(REGISTERED_IDENTITY_HASH, null))
            when (decision) {
                PushRegistrationLifecycle.Decision.AlreadyRegistered -> { diag("REGISTRATION_READY=true"); diag("REGISTER_SUCCESS=true (already current)"); return@withLock }
                PushRegistrationLifecycle.Decision.InFlight,
                PushRegistrationLifecycle.Decision.TokenMissing,
                PushRegistrationLifecycle.Decision.SubscriptionMissing -> return@withLock
                is PushRegistrationLifecycle.Decision.Start -> Unit
            }
            val registrationKey = (decision as PushRegistrationLifecycle.Decision.Start).key
            val payload = JSONObject().apply {
                put("installation_id", installationId(appContext)); put("device_id", androidDeviceId); put("token", token)
                put("platform", "android"); put("app_version", BuildConfig.VERSION_NAME); put("enabled", enabled)
                put("preferences", JSONObject().apply {
					put("enabled", enabled); put("subscription_updates", settings.pushSubscriptionUpdates.first()); put("subscription_reminders", settings.pushSubscriptionReminders.first())
                    put("security_alerts", settings.pushSecurity.first()); put("product_updates", settings.pushUpdates.first()); put("promotions", settings.pushPromotions.first())
					put("schema_version", 2)
                })
                put("metadata", JSONObject().apply { put("sdk", Build.VERSION.SDK_INT.toString()) })
            }.toString()
            diag("REGISTRATION_READY=true"); diag("REGISTER_START"); diag("REGISTER_AUTH=SUBSCRIPTION")
            var retry = false
            try {
                repeat(3) { attempt ->
                    val result = post(url, subscriptionPassword, payload)
                    if (result.error != null) diag("REGISTER_ERROR=${safeError(result.error)}") else diag("REGISTER_RESPONSE=HTTP_${result.status}")
                    when {
                        result.status in 200..299 -> {
                            prefs.edit().putString(REGISTERED_TOKEN_HASH, tokenHash).putString(REGISTERED_IDENTITY_HASH, identityHash).apply()
                            retryJob?.cancel(); retryJob = null; diag("REGISTER_SUCCESS"); return@withLock
                        }
                        result.status == 401 || result.status == 403 -> return@withLock
                        attempt < 2 -> delay((attempt + 1) * 1_000L)
                        else -> retry = true
                    }
                }
            } finally { lifecycle.finish(registrationKey) }
            if (retry) scheduleRetry(appContext, tokenHash, identityHash)
        }
    }

    private fun scheduleRetry(context: Context, tokenHash: String, identityHash: String) {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(RETRY_DELAY_MS)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pending = prefs.getString(PENDING_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return@launch
            val current = identity(context) ?: return@launch
            if (fingerprint(pending) == tokenHash && fingerprint(current.second + "\u0000" + current.third) == identityHash) { retryJob = null; register(context, pending) }
        }
    }

    fun registerCurrentToken(context: Context) {
        val appContext = context.applicationContext
        diag("TOKEN_REQUEST_START")
        val task = runCatching { FirebaseMessaging.getInstance().token }.onFailure { error ->
            diag("TOKEN_ERROR=${safeError(error)}")
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING_TOKEN, null)?.let { pending -> scope.launch { register(appContext, pending) } }
        }.getOrNull() ?: return
        task.addOnSuccessListener { token -> scope.launch { register(appContext, token) } }.addOnFailureListener { error ->
            diag("TOKEN_ERROR=${safeError(error)}")
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING_TOKEN, null)?.let { pending -> scope.launch { register(appContext, pending) } }
        }
    }

    /** Called after wdtt:// import or profile application. */
    fun onSubscriptionReady(context: Context) {
        diag("SUBSCRIPTION_READY_CALLBACK")
        val pending = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING_TOKEN, null)?.takeIf { it.isNotBlank() }
        if (pending != null) scope.launch { register(context.applicationContext, pending) } else registerCurrentToken(context.applicationContext)
    }

    suspend fun unregister(context: Context, subscriptionPasswordOverride: String? = null, peerOverride: String? = null) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val appContext = context.applicationContext; val settings = SettingsStore(appContext)
            val peer = (peerOverride ?: settings.peer.first()).trim(); val password = (subscriptionPasswordOverride ?: settings.connectionPassword.first()).trim()
            if (peer.isBlank() || password.isBlank()) return@withLock
            val port = if (settings.manualPortsEnabled.first()) settings.serverDtlsPort.first() else 56000
            val body = JSONObject().put("installation_id", installationId(appContext)).put("device_id", deviceId(appContext)).toString()
            post("http://${PeerAddress.httpEndpoint(peer, port)}/api/push/unregister", password, body)
            lifecycle.clear(); appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(REGISTERED_TOKEN_HASH).remove(REGISTERED_IDENTITY_HASH).apply()
        }
    }

    suspend fun updatePreferences(context: Context, enabled: Boolean, subscriptionUpdates: Boolean, reminders: Boolean, updates: Boolean, security: Boolean, promotions: Boolean) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext; val current = identity(appContext) ?: return@withContext; val settings = SettingsStore(appContext)
        val port = if (settings.manualPortsEnabled.first()) settings.serverDtlsPort.first() else 56000
		val prefs = JSONObject().apply { put("enabled", enabled); put("subscription_updates", subscriptionUpdates); put("subscription_reminders", reminders); put("product_updates", updates); put("security_alerts", security); put("promotions", promotions); put("schema_version", 2) }
        val body = JSONObject().put("installation_id", installationId(appContext)).put("device_id", current.third).put("preferences", prefs).toString()
        post("http://${PeerAddress.httpEndpoint(current.first, port)}/api/push/preferences", current.second, body)
    }

    private data class PostResult(val status: Int, val error: Throwable? = null)
    private fun post(url: String, credential: String, body: String): PostResult = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.connectTimeout = 5_000; connection.readTimeout = 5_000; connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $credential"); connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }; connection.responseCode.also { connection.disconnect() }
    }.fold(onSuccess = { PostResult(it) }, onFailure = { PostResult(0, it) })
}
