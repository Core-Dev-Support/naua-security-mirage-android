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
        try {
            val url = "${SupabaseConfig.SUPABASE_URL}/functions/v1/node-status?node=$node"
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
                val json = JSONObject(body)
                Status(
                    state = json.optString("state", "unknown"),
                    detail = json.optString("detail").takeIf { it.isNotBlank() && it != "null" },
                    core = json.optString("core").takeIf { it.isNotBlank() && it != "null" },
                    changedAt = parseIso(json.optString("changed_at")),
                    reportedAt = parseIso(json.optString("reported_at")),
                )
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
