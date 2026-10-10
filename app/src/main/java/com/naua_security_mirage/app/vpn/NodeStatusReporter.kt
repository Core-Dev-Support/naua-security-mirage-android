package com.naua_security_mirage.app.vpn

import android.util.Log
import com.naua_security_mirage.app.data.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object NodeStatusReporter {

    private const val TAG = "NodeStatus"

    data class Status(
        val state: String,
        val detail: String?,
        val core: String?,
        val changedAt: Long?,
        val reportedAt: Long?
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun fetch(node: String = "france"): Status? = withContext(Dispatchers.IO) {
        val json = get(node, "") ?: return@withContext null
        Status(
            state = json.optString("state", "unknown"),
            detail = json.optString("detail").takeIf { it.isNotBlank() && it != "null" },
            core = json.optString("core").takeIf { it.isNotBlank() && it != "null" },
            changedAt = parseIso(json.optString("changed_at")),
            reportedAt = parseIso(json.optString("reported_at")),
        )
    }

    data class Event(val state: String, val changedAt: Long?)

    suspend fun history(node: String = "france", limit: Int = 5): List<Event>? = withContext(Dispatchers.IO) {
        val json = get(node, "history=1&limit=$limit") ?: return@withContext null
        val arr = json.optJSONArray("events") ?: return@withContext emptyList()
        val out = ArrayList<Event>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Event(
                state = o.optString("state", "unknown"),
                changedAt = parseIso(o.optString("changed_at")),
            )
        }
        out
    }

    private fun get(node: String, query: String): JSONObject? {
        return try {
            val url = buildString {
                append(SupabaseConfig.SUPABASE_URL)
                append("/functions/v1/node-status?node=")
                append(node)
                if (query.isNotEmpty()) {
                    append('&')
                    append(query)
                }
            }
            val key = SupabaseConfig.getAnonKey()
            val request = Request.Builder()
                .url(url)
                .header("apikey", key)
                .header("authorization", "Bearer $key")
                .header("User-Agent", "NAUA-Security-Mirage")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "node-status responded ${response.code}")
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                JSONObject(body)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "node-status fetch failed: ${t.message}")
            null
        }
    }

    private fun parseIso(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) parser.timeZone = TimeZone.getTimeZone("UTC")
                val parsed: Date? = parser.parse(value)
                if (parsed != null) return parsed.time
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
