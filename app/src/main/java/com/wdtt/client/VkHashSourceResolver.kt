package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.flow.first

data class ResolvedVkHashes(
    val source: String,
    val hashes: String,
    val errorMessage: String? = null,
    val usedServerCache: Boolean = false,
    val serverHashesFetchedFreshFromApi: Boolean = false,
    val serverCacheFallbackHashes: String = "",
)

object VkHashSourceResolver {
    private const val TAG = "VKHASH"

    private fun normalizeUsableServerHashes(raw: String?): String {
        return SettingsStore.normalizeVkHashes(raw)
            .split(Regex("[,\\s\\n]+"))
            .map { stripVkUrlStatic(it) }
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
            .joinToString(",")
    }

    suspend fun resolveForConnection(
        context: Context,
        settingsStore: SettingsStore,
        peer: String,
    ): ResolvedVkHashes {
        val source = SettingsStore.normalizeVkHashSource(settingsStore.vkHashSource.first())
        android.util.Log.d(
            TAG,
            "resolveForConnection start: source=$source, peerRaw=$peer"
        )
        return when (source) {
            SettingsStore.VK_HASH_SOURCE_LOCAL -> {
                val normalizedLocalHashes =
                    SettingsStore.normalizeVkHashes(settingsStore.localVkHashes.first())
                android.util.Log.d(
                    TAG,
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
                val previousServerCache = normalizeUsableServerHashes(
                    settingsStore.serverVkHashesCache.first()
                )
                val trimmedPeer = peer.trim()
                val host = PeerAddress.host(trimmedPeer)
                if (host.isBlank()) {
                    android.util.Log.e(
                        TAG,
                        "resolveForConnection SERVER aborted: host is blank, peerRaw=$peer, trimmedPeer=$trimmedPeer"
                    )
                    return ResolvedVkHashes(
                        source = source,
                        hashes = "",
                        errorMessage = "Адрес сервера не задан"
                    )
                }
                val manualPortsEnabled = settingsStore.manualPortsEnabled.first()
                val defaultServerDtlsPort =
                    if (manualPortsEnabled) settingsStore.serverDtlsPort.first() else 56000
                val serverEndpoint = PeerAddress.httpEndpoint(trimmedPeer, defaultServerDtlsPort)
                val effectivePort = PeerAddress.port(trimmedPeer) ?: defaultServerDtlsPort
                val token = AdminSession.getToken(context) ?: ""
                android.util.Log.d(
                    TAG,
                    "resolveForConnection SERVER: host=$host, port=$effectivePort, endpoint=$serverEndpoint, hasAuthorizationBearer=${token.isNotBlank()}, manualPortsEnabled=$manualPortsEnabled"
                )

                val loadResult = ServerVkHashes.load(
                    server = serverEndpoint,
                    token = token,
                )

                if (loadResult.isSuccess) {
                    val normalized = normalizeUsableServerHashes(loadResult.hashes.joinToString(","))
                    android.util.Log.i(
                        TAG,
                        "SERVER hash получены: endpoint=$serverEndpoint, count=${loadResult.hashes.size}, normalizedLength=${normalized.length}"
                    )
                    if (normalized.isNotBlank()) {
                        val saved = settingsStore.saveServerVkHashesCacheSnapshot(normalized)
                        if (saved) {
                            android.util.Log.i(
                                TAG,
                                "SERVER hash сохранены в cache: count=${normalized.split(',').count { it.isNotBlank() }}"
                            )
                        }
                        settingsStore.saveActiveVkHashes(normalized)
                        return ResolvedVkHashes(
                            source = source,
                            hashes = normalized,
                            serverHashesFetchedFreshFromApi = true,
                            serverCacheFallbackHashes = previousServerCache.takeIf {
                                it.isNotBlank() && it != normalized
                            }.orEmpty(),
                        )
                    }
                }

                val failureMessage = loadResult.errorMessage ?: "Не удалось получить серверные VK hash"
                android.util.Log.w(
                    TAG,
                    "SERVER API недоступен или вернул невалидные данные: endpoint=$serverEndpoint, failureType=${loadResult.failureType}, message=$failureMessage"
                )
                if (previousServerCache.isNotBlank()) {
                    android.util.Log.w(
                        TAG,
                        "Используется SERVER cache: count=${previousServerCache.split(',').count { it.isNotBlank() }}"
                    )
                    settingsStore.saveActiveVkHashes(previousServerCache)
                    return ResolvedVkHashes(
                        source = source,
                        hashes = previousServerCache,
                        errorMessage = failureMessage,
                        usedServerCache = true,
                    )
                }

                android.util.Log.e(
                    TAG,
                    "SERVER cache отсутствует или невалиден: endpoint=$serverEndpoint"
                )
                ResolvedVkHashes(
                    source = source,
                    hashes = "",
                    errorMessage = failureMessage,
                )
            }
        }
    }
}
