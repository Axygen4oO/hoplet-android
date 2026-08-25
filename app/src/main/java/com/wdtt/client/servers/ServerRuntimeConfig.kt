package com.wdtt.client.servers

import com.wdtt.client.SecureApiClient
import java.io.Serializable

data class ServerRuntimeConfig(
    val serverId: String,
    val host: String,
    val dtlsPort: Int,
    val wgPort: Int,
    val httpsPort: Int,
    val tlsPin: String,
    val dns1: String,
    val dns2: String,
    val connectionPassword: String,
) : Serializable {
    fun toSecureApiConfig() = SecureApiClient.resolveConfig(
        peer = host,
        httpsPort = httpsPort,
        certPin = tlsPin,
    )

    companion object {
        fun from(server: ServerProfile, connectionPassword: String?): ServerRuntimeConfig {
            val normalized = server.normalized()
            return ServerRuntimeConfig(
                serverId = normalized.id,
                host = normalized.host,
                dtlsPort = normalized.dtlsPort,
                wgPort = normalized.wgPort,
                httpsPort = normalized.httpsPort,
                tlsPin = normalized.tlsPin.orEmpty(),
                dns1 = normalized.dns1,
                dns2 = normalized.dns2,
                connectionPassword = connectionPassword.orEmpty(),
            )
        }
    }
}
