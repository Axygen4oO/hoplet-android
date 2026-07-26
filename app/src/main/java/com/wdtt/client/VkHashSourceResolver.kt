package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.flow.first

data class ResolvedVkHashes(
    val source: String,
    val hashes: String,
)

object VkHashSourceResolver {
    suspend fun resolveForConnection(
        context: Context,
        settingsStore: SettingsStore,
        peer: String,
    ): ResolvedVkHashes {
        val source = SettingsStore.normalizeVkHashSource(settingsStore.vkHashSource.first())
        return when (source) {
            SettingsStore.VK_HASH_SOURCE_LOCAL -> {
                ResolvedVkHashes(
                    source = source,
                    hashes = SettingsStore.normalizeVkHashes(settingsStore.localVkHashes.first()),
                )
            }

            else -> {
                val host = PeerAddress.host(peer.trim())
                if (host.isBlank()) {
                    return ResolvedVkHashes(source = source, hashes = "")
                }

                val serverHashes = ServerVkHashes.load(
                    server = "$host:56000",
                    token = AdminSession.getToken(context) ?: "",
                )
                val normalized = SettingsStore.normalizeVkHashes(serverHashes.joinToString(","))
                if (normalized.isNotBlank()) {
                    settingsStore.saveServerVkHashesCache(normalized)
                }
                ResolvedVkHashes(source = source, hashes = normalized)
            }
        }
    }
}
