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
        android.util.Log.d(
            "VKHASH",
            "resolveForConnection start: source=$source, peerRaw=$peer"
        )
        return when (source) {
            SettingsStore.VK_HASH_SOURCE_LOCAL -> {
                val normalizedLocalHashes =
                    SettingsStore.normalizeVkHashes(settingsStore.localVkHashes.first())
                android.util.Log.d(
                    "VKHASH",
                    "resolveForConnection LOCAL: hashesLength=${normalizedLocalHashes.length}, isBlank=${normalizedLocalHashes.isBlank()}"
                )
                if (normalizedLocalHashes.isNotBlank()) {
                    settingsStore.saveActiveVkHashes(normalizedLocalHashes)
                }
                ResolvedVkHashes(
                    source = source,
                    hashes = normalizedLocalHashes,
                )
            }

            else -> {
                val trimmedPeer = peer.trim()
                val host = PeerAddress.host(trimmedPeer)
                if (host.isBlank()) {
                    android.util.Log.e(
                        "VKHASH",
                        "resolveForConnection SERVER aborted: host is blank, peerRaw=$peer, trimmedPeer=$trimmedPeer"
                    )
                    return ResolvedVkHashes(source = source, hashes = "")
                }
                val manualPortsEnabled = settingsStore.manualPortsEnabled.first()
                val defaultServerDtlsPort =
                    if (manualPortsEnabled) settingsStore.serverDtlsPort.first() else 56000
                val serverEndpoint = PeerAddress.httpEndpoint(trimmedPeer, defaultServerDtlsPort)
                val effectivePort = PeerAddress.port(trimmedPeer) ?: defaultServerDtlsPort
                val token = AdminSession.getToken(context) ?: ""
                android.util.Log.d(
                    "VKHASH",
                    "resolveForConnection SERVER: host=$host, port=$effectivePort, endpoint=$serverEndpoint, hasAuthorizationBearer=${token.isNotBlank()}, manualPortsEnabled=$manualPortsEnabled"
                )

                val serverHashes = ServerVkHashes.load(
                    server = serverEndpoint,
                    token = token,
                )
                val normalized = SettingsStore.normalizeVkHashes(serverHashes.joinToString(","))
                android.util.Log.d(
                    "VKHASH",
                    "resolveForConnection SERVER result: hashesCount=${serverHashes.size}, normalizedLength=${normalized.length}, isBlank=${normalized.isBlank()}"
                )
                if (normalized.isNotBlank()) {
                    settingsStore.saveServerVkHashesCache(normalized)
                    settingsStore.saveActiveVkHashes(normalized)
                } else {
                    android.util.Log.e(
                        "VKHASH",
                        "resolveForConnection SERVER returning blank hashes for endpoint=$serverEndpoint"
                    )
                }
                ResolvedVkHashes(source = source, hashes = normalized)
            }
        }
    }
}
