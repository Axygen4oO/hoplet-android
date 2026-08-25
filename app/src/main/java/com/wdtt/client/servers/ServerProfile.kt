package com.wdtt.client.servers

import com.wdtt.client.PeerAddress
import com.wdtt.client.SecureApiClient

data class ServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val sshUser: String = "",
    val sshPort: Int = DEFAULT_SSH_PORT,
    val dns1: String = DEFAULT_DNS1,
    val dns2: String = DEFAULT_DNS2,
    val dtlsPort: Int = DEFAULT_DTLS_PORT,
    val wgPort: Int = DEFAULT_WG_PORT,
    val httpsPort: Int = SecureApiClient.DEFAULT_HTTPS_PORT,
    val tlsPin: String? = null,
    val manualPortsEnabled: Boolean = false,
) {
    fun normalized(): ServerProfile {
        val normalizedHost = PeerAddress.host(host.trim())
        val normalizedName = name.trim().ifBlank { normalizedHost }
        val normalizedPin = SecureApiClient.normalizePin(tlsPin).ifBlank { null }
        return copy(
            name = normalizedName,
            host = normalizedHost,
            sshUser = sshUser.trim(),
            sshPort = sshPort.normalizePort(DEFAULT_SSH_PORT),
            dns1 = dns1.trim().ifBlank { DEFAULT_DNS1 },
            dns2 = dns2.trim().ifBlank { DEFAULT_DNS2 },
            dtlsPort = dtlsPort.normalizePort(DEFAULT_DTLS_PORT),
            wgPort = wgPort.normalizePort(DEFAULT_WG_PORT),
            httpsPort = httpsPort.normalizePort(SecureApiClient.DEFAULT_HTTPS_PORT),
            tlsPin = normalizedPin,
        )
    }

    companion object {
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_DNS1 = "1.1.1.1"
        const val DEFAULT_DNS2 = "1.0.0.1"
        const val DEFAULT_DTLS_PORT = 56000
        const val DEFAULT_WG_PORT = 56001
    }
}

private fun Int.normalizePort(fallback: Int): Int {
    return takeIf { it in 1..65535 } ?: fallback
}
