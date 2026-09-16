package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ServerNotification(
    val id: Long,
    val createdAt: Long,
    val title: String,
    val message: String,
    val type: String = "CUSTOM",
    val deepLink: String = "",
)

data class ServerNotificationState(
    val notifications: List<ServerNotification> = emptyList(),
    val readIds: Set<Long> = emptySet(),
    val latestServerId: Long = 0L,
) {
    val unreadCount: Int get() = notifications.count { it.id !in readIds }
}

internal object ServerNotificationReducer {
    const val MAX_HISTORY = 5

    private fun normalized(incoming: List<ServerNotification>): List<ServerNotification> = incoming
        .asSequence()
        // A push may intentionally have an empty body. Keep it in the local
        // cache so the same notification_id is still deduplicated and tapping
        // it opens Notification Center consistently.
        .filter { it.id > 0L && it.title.isNotBlank() }
        .distinctBy { it.id }
        .sortedByDescending { it.id }
        .take(MAX_HISTORY)
        .toList()

    fun merge(state: ServerNotificationState, incoming: List<ServerNotification>): ServerNotificationState {
        val merged = normalized(incoming + state.notifications)
        return state.copy(
            notifications = merged,
            latestServerId = maxOf(state.latestServerId, incoming.maxOfOrNull { it.id } ?: 0L),
        )
    }

    fun replaceFromServer(
        state: ServerNotificationState,
        snapshot: List<ServerNotification>,
    ): ServerNotificationState {
        val notifications = normalized(snapshot)
        val serverIds = notifications.mapTo(linkedSetOf()) { it.id }
        return ServerNotificationState(
            notifications = notifications,
            readIds = state.readIds.intersect(serverIds),
            latestServerId = notifications.maxOfOrNull { it.id } ?: 0L,
        )
    }

    fun mergeHistory(state: ServerNotificationState, incoming: List<ServerNotification>): ServerNotificationState {
        val previouslySeen = incoming.asSequence()
            .filter { it.id <= state.latestServerId }
            .map { it.id }
            .toSet()
        return merge(state.copy(readIds = state.readIds + previouslySeen), incoming)
    }

    fun markAllRead(state: ServerNotificationState): ServerNotificationState = state.copy(
		readIds = (state.readIds + state.notifications.map { it.id }).toList().takeLast(50).toSet(),
    )
}

internal object ServerNotificationCacheCodec {
    fun encode(state: ServerNotificationState): String = JSONObject().apply {
        put("latest_server_id", state.latestServerId)
        put("read_ids", JSONArray(state.readIds.sorted()))
        put("notifications", JSONArray().apply {
            state.notifications.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("created_at", item.createdAt)
                    put("title", item.title)
                    put("message", item.message)
                    put("type", item.type)
                    put("deep_link", item.deepLink)
                })
            }
        })
    }.toString()

    fun decode(raw: String?): ServerNotificationState {
        if (raw.isNullOrBlank()) return ServerNotificationState()
        return runCatching {
            val root = JSONObject(raw)
            val readIds = root.optJSONArray("read_ids").toLongSet()
            val notifications = root.optJSONArray("notifications").toNotifications()
            ServerNotificationReducer.merge(
                ServerNotificationState(
                    readIds = readIds,
                    latestServerId = root.optLong("latest_server_id", 0L),
                ),
                notifications,
            )
        }.getOrDefault(ServerNotificationState())
    }

    private fun JSONArray?.toLongSet(): Set<Long> {
        if (this == null) return emptySet()
        return buildSet { for (index in 0 until length()) optLong(index).takeIf { it > 0L }?.let(::add) }
    }

    private fun JSONArray?.toNotifications(): List<ServerNotification> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(ServerNotification(item.optLong("id"), item.optLong("created_at"), item.optString("title").trim(), item.optString("message").trim(), item.optString("type", "CUSTOM"), item.optString("deep_link")))
            }
        }
    }
}

class ServerNotificationStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()
    private val mutableState = MutableStateFlow(ServerNotificationCacheCodec.decode(preferences.getString(KEY_CACHE, null)))
    val state: StateFlow<ServerNotificationState> = mutableState.asStateFlow()

    fun merge(incoming: List<ServerNotification>): Boolean = synchronized(lock) {
        val before = mutableState.value
        val after = ServerNotificationReducer.merge(before, incoming)
		val changed = after != before
		if (changed) persist(after)
		changed
    }

	fun mergeHistory(incoming: List<ServerNotification>): Boolean = synchronized(lock) {
		val before = mutableState.value
		val after = ServerNotificationReducer.mergeHistory(before, incoming)
		val changed = after != before
		if (changed) persist(after)
		changed
	}

    fun replaceFromServer(snapshot: List<ServerNotification>): Boolean = synchronized(lock) {
        val before = mutableState.value
        val after = ServerNotificationReducer.replaceFromServer(before, snapshot)
        val changed = after != before
        if (changed) persist(after)
        changed
    }

    fun markAllRead() = synchronized(lock) {
        val after = ServerNotificationReducer.markAllRead(mutableState.value)
        if (after != mutableState.value) persist(after)
    }

    fun migrateLegacyLatestId(id: Long) = synchronized(lock) {
        if (id <= mutableState.value.latestServerId) return
        persist(mutableState.value.copy(latestServerId = id))
    }

    private fun persist(value: ServerNotificationState) {
        preferences.edit().putString(KEY_CACHE, ServerNotificationCacheCodec.encode(value)).apply()
        mutableState.value = value
    }

    companion object {
        private const val PREFERENCES = "server_notification_center"
        private const val KEY_CACHE = "cache_v1"
        @Volatile private var instance: ServerNotificationStore? = null

        fun get(context: Context): ServerNotificationStore = instance ?: synchronized(this) {
            instance ?: ServerNotificationStore(context).also { instance = it }
        }
    }
}
