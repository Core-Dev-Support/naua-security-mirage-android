package com.naua_security_mirage.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SessionJournal {

    data class Entry(val startedAt: Long, val node: String, val seconds: Long)

    private const val PREFS = "mirage_sessions"
    private const val KEY_ENTRIES = "sessions_json"
    private const val MAX_ENTRIES = 50

    fun add(context: Context, nodeTag: String, seconds: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val list = readEntries(prefs.getString(KEY_ENTRIES, null)).toMutableList()
            list.add(
                0,
                Entry(
                    startedAt = System.currentTimeMillis() - seconds * 1000L,
                    node = nodeTag.take(64),
                    seconds = seconds.coerceAtLeast(0L)
                )
            )
            while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
            val array = JSONArray()
            for (entry in list) {
                array.put(
                    JSONObject()
                        .put("at", entry.startedAt)
                        .put("node", entry.node)
                        .put("sec", entry.seconds)
                )
            }
            prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    fun list(context: Context): List<Entry> {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            readEntries(prefs.getString(KEY_ENTRIES, null))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readEntries(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    startedAt = obj.optLong("at", 0L),
                    node = obj.optString("node", ""),
                    seconds = obj.optLong("sec", 0L)
                )
            }.filter { it.startedAt > 0 }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
