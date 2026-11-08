package com.naua_security_mirage.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SessionJournal {

    data class Entry(val startedAt: Long, val node: String, val seconds: Long)

    private val storageFile: File by lazy {
        val dir = File(System.getProperty("user.home"), ".naua-mirage")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "sessions.json")
    }

    private const val MAX_ENTRIES = 50

    fun add(nodeTag: String, seconds: Long) {
        if (seconds < 2) return
        try {
            val list = list().toMutableList()
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
            storageFile.writeText(array.toString())
        } catch (_: Throwable) {}
    }

    fun list(): List<Entry> {
        return try {
            if (!storageFile.exists()) return emptyList()
            val raw = storageFile.readText()
            if (raw.isBlank()) return emptyList()
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    startedAt = obj.optLong("at", 0L),
                    node = obj.optString("node", ""),
                    seconds = obj.optLong("sec", 0L)
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
