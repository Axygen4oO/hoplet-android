package com.wdtt.client

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object AppDnsResolver {
    private const val TAG = "AppDnsResolver"
    private const val DNS_PORT = 53
    private const val DEFAULT_TIMEOUT_MS = 2500
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val UDP_PROBE_TIMEOUT_MS = 600
    private const val UDP_PROBE_TTL_MS = 30_000L
    private const val PROBE_HOST = "example.com"

    private val dnsMessageType = "application/dns-message".toMediaType()

    private val udpReachabilityCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private val resolveCache = ConcurrentHashMap<String, CacheEntry>()

    private val bypassExtraUdpServers = listOf("1.1.1.1", "8.8.8.8")

    private data class CacheEntry(
        val resolvedAtMs: Long,
        val addresses: Set<Ipv4Cidr>,
    )

    fun lookupAForBypass(goDnsArg: String, hostname: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Set<Ipv4Cidr> {
        val host = hostname.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return emptySet()

        val cacheKey = "${goDnsArg.trim()}|$host"
        val now = System.currentTimeMillis()
        val cached = resolveCache[cacheKey]
        if (cached != null && now - cached.resolvedAtMs < CACHE_TTL_MS && cached.addresses.isNotEmpty()) {
            Log.d(TAG, "[AppDnsResolver] cache hit for $host, count=${cached.addresses.size}")
            return cached.addresses
        }

        val resolved = linkedSetOf<Ipv4Cidr>()
        var hadError = false

        runCatching {
            lookupViaSystem(host)
        }.onSuccess { resolved.addAll(it) }
            .onFailure {
                hadError = true
                Log.w(TAG, "[AppDnsResolver] system DNS failed for $host: ${it.message}")
            }

        val primary = runCatching {
            val isDoh = SettingsStore.isDohGoDnsPreset(SettingsStore.goDnsDisplayFromArg(goDnsArg).preset) ||
                goDnsArg.trim().startsWith("doh:", ignoreCase = true)
            if (isDoh) {
                lookupViaDoh(goDnsArg, host, timeoutMs)
            } else {
                lookupViaUdp(goDnsArg, host, timeoutMs)
            }
        }.onFailure {
            hadError = true
            Log.w(TAG, "[AppDnsResolver] configured DNS failed for $host: ${it.message}")
        }.getOrDefault(emptySet())
        resolved.addAll(primary)

        for (server in bypassExtraUdpServers) {
            if (!isUdpServerReachable(server)) continue
            val fallback = runCatching {
                queryUdpA(server, host, timeoutMs)
            }.onFailure {
                hadError = true
                Log.w(TAG, "[AppDnsResolver] fallback UDP $server failed for $host: ${it.message}")
            }.getOrDefault(emptySet())
            resolved.addAll(fallback)
        }

        val effective = pickEffectiveAddresses(cached?.addresses, resolved)
        if (effective.isNotEmpty()) {
            resolveCache[cacheKey] = CacheEntry(now, effective)
            if (resolved.isEmpty() && cached != null && cached.addresses.isNotEmpty()) {
                Log.w(TAG, "[AppDnsResolver] using stale cache for $host, count=${cached.addresses.size}")
            } else {
                Log.d(TAG, "[AppDnsResolver] resolved $host via union, count=${effective.size}")
            }
            return effective
        }

        Log.w(TAG, "[AppDnsResolver] no IPv4 answers for $host")
        return emptySet()
    }

    internal fun pickEffectiveAddresses(
        cachedAddresses: Set<Ipv4Cidr>?,
        resolvedAddresses: Set<Ipv4Cidr>,
    ): Set<Ipv4Cidr> {
        return if (resolvedAddresses.isNotEmpty()) resolvedAddresses else cachedAddresses.orEmpty()
    }

    private fun lookupViaSystem(hostname: String): Set<Ipv4Cidr> {
        return InetAddress.getAllByName(hostname)
            .mapNotNull { addr ->
                val bytes = addr.address
                if (bytes.size == 4) Ipv4Cidr.fromAddress(bytes) else null
            }
            .toSet()
    }

    private fun lookupViaUdp(goDnsArg: String, hostname: String, timeoutMs: Int): Set<Ipv4Cidr> {
        val servers = SettingsStore.goDnsDisplayFromArg(goDnsArg).servers
            .ifEmpty { SettingsStore.goDnsDisplay("yandex").servers }
        val out = linkedSetOf<Ipv4Cidr>()
        for (server in servers) {
            if (!isUdpServerReachable(server)) continue
            val addrs = runCatching {
                queryUdpA(server, hostname, timeoutMs)
            }.onFailure {
                Log.w(TAG, "[AppDnsResolver] UDP DNS $server failed for $hostname: ${it.message}")
            }.getOrDefault(emptySet())
            out.addAll(addrs)
        }
        return out
    }

    private fun lookupViaDoh(goDnsArg: String, hostname: String, timeoutMs: Int): Set<Ipv4Cidr> {
        val out = linkedSetOf<Ipv4Cidr>()
        for (endpoint in dohEndpoints(goDnsArg)) {
            val addrs = runCatching {
                queryDohA(endpoint, hostname, timeoutMs)
            }.onFailure {
                Log.w(TAG, "[AppDnsResolver] DoH $endpoint failed for $hostname: ${it.message}")
            }.getOrDefault(emptySet())
            out.addAll(addrs)
        }
        return out
    }

    private fun dohEndpoints(goDnsArg: String): List<String> {
        val display = SettingsStore.goDnsDisplayFromArg(goDnsArg)
        return when (display.preset) {
            "doh-cloudflare" -> listOf(
                "https://1.1.1.1/dns-query",
                "https://cloudflare-dns.com/dns-query",
            )
            "doh-google" -> listOf(
                "https://8.8.8.8/dns-query",
                "https://dns.google/dns-query",
            )
            "doh-yandex" -> listOf(
                "https://77.88.8.8/dns-query",
                "https://common.dot.dns.yandex.net/dns-query",
            )
            "doh-custom" -> display.servers.map { normalizeDohEndpoint(it) }
            else -> listOf(
                "https://77.88.8.8/dns-query",
                "https://common.dot.dns.yandex.net/dns-query",
            )
        }
    }

    private fun normalizeDohEndpoint(target: String): String {
        val trimmed = target.trim()
        return if (trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed/dns-query"
        }
    }

    private fun isUdpServerReachable(serverIp: String): Boolean {
        val now = System.currentTimeMillis()
        val cached = udpReachabilityCache[serverIp]
        if (cached != null && now - cached.first < UDP_PROBE_TTL_MS) {
            return cached.second
        }

        val reachable = runCatching {
            queryUdpA(serverIp, PROBE_HOST, UDP_PROBE_TIMEOUT_MS).isNotEmpty()
        }.getOrDefault(false)
        udpReachabilityCache[serverIp] = now to reachable
        return reachable
    }

    private fun queryUdpA(serverIp: String, hostname: String, timeoutMs: Int): Set<Ipv4Cidr> {
        val txId = Random.nextInt(0, 0x10000)
        val query = buildDnsQuery(txId, hostname)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs.coerceIn(500, 8000)
            val address = InetAddress.getByName(serverIp)
            socket.send(DatagramPacket(query, query.size, address, DNS_PORT))
            val buffer = ByteArray(2048)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            return parseDnsARecords(response.data, response.length, txId)
        }
    }

    private fun queryDohA(endpoint: String, hostname: String, timeoutMs: Int): Set<Ipv4Cidr> {
        val txId = Random.nextInt(0, 0x10000)
        val query = buildDnsQuery(txId, hostname)
        val timeout = timeoutMs.coerceIn(500, 8000).toLong()
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout + 500, TimeUnit.MILLISECONDS)
            .build()

        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("Accept", "application/dns-message")
            .post(query.toRequestBody(dnsMessageType))
        dohHostHeader(endpoint)?.let { builder.header("Host", it) }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return emptySet()
            val body = response.body?.bytes() ?: return emptySet()
            return parseDnsARecords(body, body.size, txId)
        }
    }

    private fun dohHostHeader(endpoint: String): String? {
        val host = try {
            URL(endpoint).host
        } catch (_: Exception) {
            return null
        }
        if (!host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))) return null
        return when (host) {
            "1.1.1.1", "1.0.0.1" -> "cloudflare-dns.com"
            "8.8.8.8", "8.8.4.4" -> "dns.google"
            "77.88.8.8", "77.88.8.1" -> "common.dot.dns.yandex.net"
            else -> null
        }
    }

    private fun buildDnsQuery(txId: Int, hostname: String): ByteArray {
        val labels = hostname.trimEnd('.').split('.')
        val qnameSize = labels.sumOf { 1 + it.length } + 1
        val packet = ByteArray(12 + qnameSize + 4)
        packet[0] = ((txId shr 8) and 0xff).toByte()
        packet[1] = (txId and 0xff).toByte()
        packet[2] = 0x01
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x01

        var offset = 12
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            packet[offset++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, packet, offset, bytes.size)
            offset += bytes.size
        }
        packet[offset++] = 0x00
        packet[offset++] = 0x00
        packet[offset++] = 0x01
        packet[offset++] = 0x00
        packet[offset] = 0x01
        return packet
    }

    private fun parseDnsARecords(data: ByteArray, length: Int, txId: Int): Set<Ipv4Cidr> {
        if (length < 12) return emptySet()
        val responseId = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        if (responseId != txId) return emptySet()

        val flags = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        if ((flags and 0x8000) == 0) return emptySet()
        if ((flags and 0x000F) != 0) return emptySet()

        val qdCount = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        val anCount = ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)

        var offset = 12
        repeat(qdCount) {
            offset = skipName(data, length, offset) ?: return emptySet()
            offset += 4
            if (offset > length) return emptySet()
        }

        val out = linkedSetOf<Ipv4Cidr>()
        repeat(anCount) {
            offset = skipName(data, length, offset) ?: return out
            if (offset + 10 > length) return out

            val type = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
            val rdLength = ((data[offset + 8].toInt() and 0xff) shl 8) or (data[offset + 9].toInt() and 0xff)
            offset += 10
            if (offset + rdLength > length) return out

            if (type == 1 && rdLength == 4) {
                out.add(
                    Ipv4Cidr.fromAddress(
                        byteArrayOf(
                            data[offset],
                            data[offset + 1],
                            data[offset + 2],
                            data[offset + 3],
                        ),
                    ),
                )
            }
            offset += rdLength
        }
        return out
    }

    private fun skipName(data: ByteArray, length: Int, start: Int): Int? {
        var offset = start
        var guard = 0
        while (guard++ < 128) {
            if (offset >= length) return null
            val label = data[offset].toInt() and 0xff
            when {
                label == 0 -> return offset + 1
                (label and 0xC0) == 0xC0 -> {
                    if (offset + 1 >= length) return null
                    return offset + 2
                }
                else -> {
                    offset += 1 + label
                    if (offset > length) return null
                }
            }
        }
        return null
    }
}
