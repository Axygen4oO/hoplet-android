package com.wdtt.client

data class DeploySuccessMarker(
    val httpsPort: Int,
    val tlsPin: String,
)

object DeployOutputParser {
    private const val SUCCESS_PREFIX = "WDTT_DEPLOY_OK|"
    private const val LEGACY_PIN_PREFIX = "WDTT_TLS_PIN|"

    fun parseSuccessMarker(output: String): DeploySuccessMarker? {
        val markerLine = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(SUCCESS_PREFIX) }
            ?: return null

        val values = markerLine
            .removePrefix(SUCCESS_PREFIX)
            .split('|')
            .mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx <= 0 || idx == token.lastIndex) {
                    null
                } else {
                    token.substring(0, idx).trim() to token.substring(idx + 1).trim()
                }
            }
            .toMap()

        val httpsPort = values["https_port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val tlsPin = SecureApiClient.normalizePin(values["tls_pin"]).takeIf { it.isNotBlank() } ?: return null
        return DeploySuccessMarker(
            httpsPort = httpsPort,
            tlsPin = tlsPin,
        )
    }

    fun extractTlsPin(output: String): String? {
        parseSuccessMarker(output)?.let { return it.tlsPin }
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(LEGACY_PIN_PREFIX) }
            ?.substringAfter(LEGACY_PIN_PREFIX)
            ?.trim()
            ?.let(SecureApiClient::normalizePin)
            ?.takeIf { it.isNotBlank() }
    }
}
