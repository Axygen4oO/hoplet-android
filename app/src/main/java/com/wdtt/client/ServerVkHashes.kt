package com.wdtt.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.SSLException

object ServerVkHashes {
    private const val TAG = "VKHASH"

    enum class FailureType {
        HTTP_ERROR,
        TIMEOUT,
        NETWORK_ERROR,
        INVALID_RESPONSE,
        UNKNOWN_ERROR,
    }

    data class LoadResult(
        val hashes: List<String>,
        val requestUrl: String,
        val failureType: FailureType? = null,
        val errorMessage: String? = null,
    ) {
        val isSuccess: Boolean
            get() = failureType == null && hashes.isNotEmpty()
    }

    suspend fun load(server: String, token: String): LoadResult =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            val requestUrl = "http://$server/api/vkhashes"
            val hasBearer = token.isNotBlank()

            fun failure(type: FailureType, reason: String): LoadResult {
                android.util.Log.e(TAG, "ServerVkHashes.load failed: type=$type, reason=$reason")
                return LoadResult(
                    hashes = emptyList(),
                    requestUrl = requestUrl,
                    failureType = type,
                    errorMessage = reason,
                )
            }

            try {
                val url = URL(requestUrl)
                android.util.Log.d(
                    TAG,
                    "ServerVkHashes.load start: url=$requestUrl, host=${url.host}, port=${url.port}, hasAuthorizationBearer=$hasBearer"
                )

                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (token.isNotBlank()) {
                    conn.setRequestProperty(
                        "Authorization",
                        "Bearer $token"
                    )
                }

                android.util.Log.d(
                    TAG,
                    "ServerVkHashes.load executing request: method=${conn.requestMethod}, connectTimeout=${conn.connectTimeout}, readTimeout=${conn.readTimeout}"
                )

                val responseCode = conn.responseCode
                val responseMessage = conn.responseMessage.orEmpty()
                val responseText = try {
                    val responseStream = if (responseCode in 200..299) {
                        conn.inputStream
                    } else {
                        conn.errorStream ?: conn.inputStream
                    }
                    responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                } catch (e: IOException) {
                    android.util.Log.e(TAG, "Failed reading /api/vkhashes response", e)
                    ""
                }
                val errorText = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: IOException) {
                    android.util.Log.e(TAG, "Failed reading /api/vkhashes errorStream", e)
                    null
                }

                android.util.Log.d(
                    TAG,
                    "ServerVkHashes.load response: code=$responseCode, message=$responseMessage, errorStream=$errorText, body=$responseText"
                )

                if (responseCode !in 200..299) {
                    return@withContext failure(
                        FailureType.HTTP_ERROR,
                        "HTTP $responseCode ($responseMessage), errorStream=$errorText, body=$responseText"
                    )
                }

                if (responseText.isBlank()) {
                    return@withContext failure(
                        FailureType.INVALID_RESPONSE,
                        "Response body is blank despite HTTP $responseCode"
                    )
                }

                android.util.Log.d(TAG, "ServerVkHashes.load raw JSON text: $responseText")
                val json = JSONObject(responseText)
                android.util.Log.d(TAG, "ServerVkHashes.load parsed JSON: ${json.toString()}")
                if (json.has("success") && !json.optBoolean("success", false)) {
                    return@withContext failure(
                        FailureType.INVALID_RESPONSE,
                        "JSON success=false, body=$responseText"
                    )
                }
                val arr = json.optJSONArray("hashes")
                if (arr == null) {
                    return@withContext failure(
                        FailureType.INVALID_RESPONSE,
                        "JSON does not contain hashes array, body=$responseText"
                    )
                }

                val parsedHashes = buildList {
                    for (i in 0 until arr.length()) {
                        val h = arr.getString(i).trim()
                        if (h.isNotEmpty()) add(h)
                    }
                }
                android.util.Log.d(TAG, "ServerVkHashes.load parsed hashes: count=${parsedHashes.size}, hashes=$parsedHashes")
                if (parsedHashes.isEmpty()) {
                    return@withContext failure(
                        FailureType.INVALID_RESPONSE,
                        "JSON hashes array parsed successfully but produced an empty list"
                    )
                }
                LoadResult(
                    hashes = parsedHashes,
                    requestUrl = requestUrl,
                )

            } catch (e: SocketTimeoutException) {
                android.util.Log.e(TAG, "ServerVkHashes.load timeout exception for url=$requestUrl", e)
                failure(FailureType.TIMEOUT, "SocketTimeoutException: ${e.message}")
            } catch (e: UnknownHostException) {
                android.util.Log.e(TAG, "ServerVkHashes.load unknown host for url=$requestUrl", e)
                failure(FailureType.NETWORK_ERROR, "UnknownHostException: ${e.message}")
            } catch (e: SSLException) {
                android.util.Log.e(TAG, "ServerVkHashes.load SSL exception for url=$requestUrl", e)
                failure(FailureType.NETWORK_ERROR, "SSLException: ${e.message}")
            } catch (e: IOException) {
                android.util.Log.e(TAG, "ServerVkHashes.load IO exception for url=$requestUrl", e)
                failure(FailureType.NETWORK_ERROR, "${e.javaClass.simpleName}: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "ServerVkHashes.load unexpected exception for url=$requestUrl", e)
                failure(FailureType.UNKNOWN_ERROR, "${e.javaClass.name}: ${e.message}")
            } finally {
                android.util.Log.d(TAG, "ServerVkHashes.load disconnect: url=$requestUrl")
                conn?.disconnect()
            }
        }
}
