package com.wdtt.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ServerVkHashes {

    suspend fun load(server: String, token: String): List<String> =
        withContext(Dispatchers.IO) {

            try {
                val url = URL("http://$server/api/vkhashes")

                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (token.isNotBlank()) {
                    conn.setRequestProperty(
                        "Authorization",
                        "Bearer $token"
                    )
                }

                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(text)
                val arr = json.getJSONArray("hashes")

                buildList {
                    for (i in 0 until arr.length()) {
                        val h = arr.getString(i).trim()
                        if (h.isNotEmpty()) add(h)
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e("VKHASH", e.stackTraceToString())
                emptyList()
            }
        }
}