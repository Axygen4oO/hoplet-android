package com.wdtt.client

import android.util.Base64
import com.wdtt.client.servers.ServerProfile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class SecureApiConfig(
    val host: String,
    val port: Int,
    val certPin: String,
)

object SecureApiClient {
    const val DEFAULT_HTTPS_PORT = 443

    private val clients = ConcurrentHashMap<String, OkHttpClient>()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun normalizePin(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("sha256/", ignoreCase = true)) {
            "sha256/${trimmed.removePrefix("sha256/").removePrefix("SHA256/").trim()}"
        } else {
            "sha256/$trimmed"
        }
    }

    fun resolveConfig(
        peer: String,
        httpsPort: Int,
        certPin: String,
    ): SecureApiConfig? {
        return resolveConfigForHost(
            host = PeerAddress.host(peer.trim()),
            httpsPort = httpsPort,
            certPin = certPin,
        )
    }

    fun resolveConfig(server: ServerProfile?): SecureApiConfig? {
        if (server == null) return null
        return resolveConfigForHost(
            host = server.host,
            httpsPort = server.httpsPort,
            certPin = server.tlsPin.orEmpty(),
        )
    }

    fun resolveConfigForHost(
        host: String,
        httpsPort: Int,
        certPin: String,
    ): SecureApiConfig? {
        val normalizedHost = PeerAddress.host(host.trim())
        if (normalizedHost.isBlank()) return null
        val normalizedPin = normalizePin(certPin)
        if (normalizedPin.isBlank()) return null
        val normalizedPort = httpsPort.takeIf { it in 1..65535 } ?: DEFAULT_HTTPS_PORT
        return SecureApiConfig(
            host = normalizedHost,
            port = normalizedPort,
            certPin = normalizedPin,
        )
    }

    fun baseUrl(config: SecureApiConfig): String {
        val safeHost = if (config.host.contains(':') && !config.host.startsWith('[')) {
            "[${config.host}]"
        } else {
            config.host
        }
        return "https://$safeHost:${config.port}"
    }

    fun jsonBody(payload: String): RequestBody = payload.toRequestBody(jsonMediaType)

    fun execute(
        config: SecureApiConfig,
        path: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): Response {
        val request = Request.Builder()
            .url(baseUrl(config) + path)
            .apply {
                headers.forEach { (name, value) ->
                    header(name, value)
                }
            }
            .method(method, body)
            .build()
        return clientFor(config).newCall(request).execute()
    }

    fun ipSubjectAltName(host: String): String? {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val address = InetAddress.getByName(trimmed)
            if (address.hostAddress == trimmed) trimmed else null
        } catch (_: UnknownHostException) {
            null
        }
    }

    private fun clientFor(config: SecureApiConfig): OkHttpClient {
        val cacheKey = "${config.host.lowercase()}|${config.port}|${config.certPin}"
        return clients.getOrPut(cacheKey) {
            val systemTrustManager = systemTrustManager()
            val trustManager = PinnedTrustManager(config.certPin, systemTrustManager)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), null)
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class PinnedTrustManager(
        pin: String,
        private val delegate: X509TrustManager,
    ) : X509TrustManager {
        private val normalizedPin = normalizePin(pin)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val serverChain = chain ?: throw CertificateException("Server certificate is missing")
            val leaf = serverChain.firstOrNull() ?: throw CertificateException("Server certificate is missing")

            try {
                delegate.checkServerTrusted(serverChain, authType.orEmpty())
                leaf.checkValidity()
                ensurePinMatches(leaf)
                return
            } catch (delegateError: CertificateException) {
                validatePinnedSelfSignedChain(serverChain, leaf, delegateError)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        private fun validatePinnedSelfSignedChain(
            chain: Array<out X509Certificate>,
            leaf: X509Certificate,
            delegateError: CertificateException,
        ) {
            if (chain.size != 1) {
                throw CertificateException(
                    "Server certificate chain is not trusted by the system CA store",
                    delegateError
                )
            }
            leaf.checkValidity()
            try {
                leaf.verify(leaf.publicKey)
            } catch (error: Exception) {
                throw CertificateException("Pinned self-signed server certificate failed signature verification", error)
            }
            ensurePinMatches(leaf)
        }

        private fun ensurePinMatches(certificate: X509Certificate) {
            val actualPin = publicKeyPin(certificate)
            if (!MessageDigest.isEqual(actualPin.toByteArray(Charsets.US_ASCII), normalizedPin.toByteArray(Charsets.US_ASCII))) {
                throw CertificateException("Server certificate pin mismatch")
            }
        }
    }

    private fun publicKeyPin(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return "sha256/${Base64.encodeToString(digest, Base64.NO_WRAP)}"
    }
}
