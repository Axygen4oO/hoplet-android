package com.wdtt.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TunnelNetworkMonitor(
    context: Context,
    private val updateNotification: (String) -> Unit,
    private val pauseTunnel: () -> Unit,
    private val resumeTunnel: (String, String?, Long) -> Unit,
    private val restartTransport: (String, String?, Long) -> Unit,
    private val stopTunnel: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkChangeTime = 0L
    private val activeNetworks = mutableSetOf<Network>()
    private val networkFingerprints = mutableMapOf<Network, String>()
    private var lastUnderlyingFingerprint = ""
    private var lastUnderlyingSummary = ""
    private var wasOnWifi = false

    var isTunnelPaused: Boolean = false
        private set

    fun start() {
        if (networkCallback != null) return

        activeNetworks.clear()
        networkFingerprints.clear()
        lastUnderlyingFingerprint = ""
        lastUnderlyingSummary = ""
        isTunnelPaused = false

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val wasEmpty = activeNetworks.isEmpty()
                activeNetworks.add(network)
                rememberNetworkFingerprint(network)

                if (wasEmpty) {
                    if (isTunnelPaused && TunnelManager.enabled.value) {
                        isTunnelPaused = false
                        Log.d("TunnelNetworkMonitor", "Сеть появилась, возобновляем туннель")
                        updateNotification("Автовосстановление TUN...")
                        resumeTunnel(
                            "сеть вернулась после обрыва",
                            activeNetworkSummary(),
                            1_500L,
                        )
                    } else {
                        noteUnderlyingNetworkChange()
                    }
                } else {
                    noteUnderlyingNetworkChange()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                activeNetworks.remove(network)
                networkFingerprints.remove(network)

                if (
                    activeNetworks.isEmpty() &&
                    shouldPauseTunnelOnNetworkLoss() &&
                    !isTunnelPaused
                ) {
                    isTunnelPaused = true
                    lastUnderlyingFingerprint = ""
                    lastUnderlyingSummary = ""
                    Log.d("TunnelNetworkMonitor", "Сеть потеряна, приостанавливаем туннель")
                    pauseTunnel()
                    updateNotification("Ожидание сети для TUN...")
                } else {
                    noteUnderlyingNetworkChange()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                if (network !in activeNetworks) return
                val previousFingerprint = networkFingerprints[network]
                val nextFingerprint = rememberNetworkFingerprint(network)
                if (previousFingerprint != null && previousFingerprint != nextFingerprint) {
                    noteUnderlyingNetworkChange()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                super.onLinkPropertiesChanged(network, linkProperties)
                if (network !in activeNetworks) return
                noteUnderlyingNetworkChange()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        connectivityManager.registerNetworkCallback(request, requireNotNull(networkCallback))
        wasOnWifi = isUnderlyingWifiActive()
    }

    fun stop() {
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                .onFailure {
                    Log.w(
                        "TunnelNetworkMonitor",
                        "Не удалось снять network callback: ${it.message}",
                    )
                }
        }
        networkCallback = null
        activeNetworks.clear()
        networkFingerprints.clear()
        lastUnderlyingFingerprint = ""
        lastUnderlyingSummary = ""
        isTunnelPaused = false
        wasOnWifi = false
    }

    private fun shouldPauseTunnelOnNetworkLoss(): Boolean {
        return TunnelManager.enabled.value &&
            (TunnelManager.running.value || TunnelManager.isConnecting.value)
    }

    private fun isUnderlyingWifiActive(): Boolean {
        return activeNetworks.any { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    private fun networkCapabilityFingerprint(caps: NetworkCapabilities): String {
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("eth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }.joinToString("+")
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return "$transports|validated=$validated|internet=$internet"
    }

    private fun activeNetworkSummary(): String {
        val transports = activeNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "другая сеть"
            }
        }.distinct()
        return transports.joinToString(", ").ifEmpty { "тип сети не определён" }
    }

    private fun rememberNetworkFingerprint(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return ""
        return networkCapabilityFingerprint(caps).also { fingerprint ->
            networkFingerprints[network] = fingerprint
        }
    }

    private fun activeUnderlyingFingerprint(): String {
        return activeNetworks.mapNotNull { network ->
            networkFingerprints[network]?.let { fingerprint -> "$network:$fingerprint" }
        }.sorted().joinToString("|")
    }

    private fun noteUnderlyingNetworkChange() {
        val fingerprint = activeUnderlyingFingerprint()
        val summary = activeNetworkSummary()
        if (fingerprint.isEmpty()) return
        if (lastUnderlyingFingerprint.isEmpty()) {
            lastUnderlyingFingerprint = fingerprint
            lastUnderlyingSummary = summary
            return
        }
        if (fingerprint == lastUnderlyingFingerprint) return
        val previousSummary = lastUnderlyingSummary
        lastUnderlyingFingerprint = fingerprint
        lastUnderlyingSummary = summary
        handleNetworkChange(previousSummary, summary)
    }

    private fun handleNetworkChange(previousSummary: String, currentSummary: String) {
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < 3_000) return
        lastNetworkChangeTime = now

        val nowOnWifi = isUnderlyingWifiActive()
        val transitionedToWifi = nowOnWifi && !wasOnWifi
        wasOnWifi = nowOnWifi
        val details = formatNetworkTransition(previousSummary, currentSummary)

        if (transitionedToWifi && TunnelManager.enabled.value && !isTunnelPaused) {
            TunnelManager.scope.launch {
                val stopOnWifi = SettingsStore(appContext).stopOnWifi.first()
                if (stopOnWifi) {
                    Log.d(
                        "TunnelNetworkMonitor",
                        "Подключились к Wi-Fi, отключаем туннель по настройке",
                    )
                    launch(Dispatchers.Main) { stopTunnel() }
                    return@launch
                }
                restartTransportIfRunning("подключились к Wi-Fi", details)
            }
            return
        }

        restartTransportIfRunning("изменилась активная сеть", details)
    }

    private fun formatNetworkTransition(previousSummary: String, currentSummary: String): String {
        val previous = previousSummary.ifBlank { "предыдущая сеть" }
        val current = currentSummary.ifBlank { "новая сеть" }
        if (previous == current) return current
        return "$previous -> $current"
    }

    private fun restartTransportIfRunning(reason: String, details: String) {
        if (TunnelManager.enabled.value && TunnelManager.running.value && !isTunnelPaused) {
            Log.d("TunnelNetworkMonitor", "Сеть изменилась, переподключаем транспорт")
            updateNotification("Автовосстановление TUN...")
            restartTransport(reason, details, 1_500L)
        }
    }
}
