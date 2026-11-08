package com.naua_security_mirage.app.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object SessionJournal {

    data class Entry(val startedAt: Long, val node: String, val seconds: Long)

    private val gson = Gson()
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
            storageFile.writeText(gson.toJson(list))
        } catch (_: Throwable) {}
    }

    fun list(): List<Entry> {
        return try {
            if (!storageFile.exists()) return emptyList()
            val raw = storageFile.readText()
            if (raw.isBlank()) return emptyList()
            val type = object : TypeToken<List<Entry>>() {}.type
            gson.fromJson<List<Entry>>(raw, type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
