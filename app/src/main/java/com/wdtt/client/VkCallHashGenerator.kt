package com.wdtt.client

import android.content.Context
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object VkCallHashGenerator {
    private const val VK_CLIENT_ID = "6287487"
    private const val API_VERSION = "5.199"
    private const val REDIRECT_URI = "https://oauth.vk.com/blank.html"

    suspend fun generateHashes(context: Context, count: Int = 1): Result<List<String>> = withContext(Dispatchers.IO) {
        if (!VkAuthWebViewManager.hasVkSessionCookie()) {
            return@withContext Result.failure(IllegalStateException("Сначала войдите в аккаунт VK"))
        }

        val token = obtainAccessToken(context)
            ?: return@withContext Result.failure(
                IllegalStateException("Не удалось получить токен VK. Перелогиньтесь в аккаунт VK.")
            )

        val hashes = mutableListOf<String>()
        val total = count.coerceIn(1, SettingsStore.MAX_VK_HASHES)
        repeat(total) { index ->
            if (index > 0) delay(2_000)
            val joinLink = startCall(token)
                ?: return@withContext Result.failure(
                    IllegalStateException("Не удалось создать звонок VK (${index + 1}/$total)")
                )
            val hash = stripVkUrlStatic(joinLink)
            if (hash.length < 16) {
                return@withContext Result.failure(
                    IllegalStateException("VK вернул некорректную ссылку на звонок")
                )
            }
            hashes.add(hash)
        }
        Result.success(hashes)
    }

    private suspend fun obtainAccessToken(context: Context): String? {
        obtainAccessTokenViaHttp(context)?.let { return it }
        return VkAuthWebViewManager.obtainAccessToken(context).getOrNull()
    }

    private fun obtainAccessTokenViaHttp(context: Context): String? {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val cookieHeader = VkAuthWebViewManager.buildVkCookieHeader()
        if (cookieHeader.isBlank()) return null

        val ua = WebSettings.getDefaultUserAgent(context)
        var url = buildAuthorizeUrl()

        repeat(12) {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val location = response.header("Location").orEmpty()
                extractAccessToken(location)?.let { return it }

                when {
                    location.isNotBlank() -> url = location
                    response.isSuccessful -> {
                        val body = response.body?.string().orEmpty()
                        Regex("""location\.href\s*=\s*["']([^"']+)["']""")
                            .find(body)?.groupValues?.getOrNull(1)
                            ?.let { href -> extractAccessToken(href)?.let { return it } }
                        Regex("""(https://login\.vk\.com/\?act=grant_access[^"'\\s<]+)""")
                            .find(body)?.groupValues?.getOrNull(1)
                            ?.replace("&amp;", "&")
                            ?.let { grantUrl -> url = grantUrl }
                            ?: return null
                    }
                    else -> return null
                }
            }
        }
        return null
    }

    private fun buildAuthorizeUrl(): String {
        val redirect = URLEncoder.encode(REDIRECT_URI, Charsets.UTF_8.name())
        return "https://oauth.vk.com/authorize" +
            "?client_id=$VK_CLIENT_ID" +
            "&display=mobile" +
            "&redirect_uri=$redirect" +
            "&response_type=token" +
            "&scope=messages" +
            "&state=wdtt" +
            "&v=$API_VERSION"
    }

    internal fun extractAccessToken(url: String): String? {
        if (!url.contains("access_token=")) return null
        val part = when {
            url.contains('#') -> url.substringAfter('#')
            url.contains('?') -> url.substringAfter('?')
            else -> url
        }
        return part.split('&')
            .firstOrNull { it.startsWith("access_token=") }
            ?.substringAfter("access_token=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun startCall(accessToken: String): String? {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.vk.ru")
            .addPathSegments("method/calls.start")
            .addQueryParameter("access_token", accessToken)
            .addQueryParameter("v", API_VERSION)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val json = JSONObject(body)
            if (json.has("error")) {
                val err = json.getJSONObject("error")
                throw IllegalStateException(err.optString("error_msg", "VK API error"))
            }
            return json.optJSONObject("response")?.optString("join_link")?.takeIf { it.isNotBlank() }
        }
    }
}
