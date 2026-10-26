package com.naua_security_mirage.app.vpn

import android.content.Context
import com.naua_security_mirage.app.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Локальная история состояний узлов, которые проверяются с устройства.
 *
 * Серверный node-status собирает события только по Франции: его пишет
 * watchdog на самом узле. Для Беларуси единственный источник правды — это
 * проверка с телефона, поэтому её результат хранится здесь и показывается в
 * карточке диагностики. Пишем только смену состояния, иначе каждая проверка
 * добавляла бы одинаковую строку.
 */
object NodeEventStore {

    const val NODE_BELARUS = "belarus"
    const val STATE_UP = "up"
    const val STATE_DOWN = "down"

    private const val TAG = "NodeEvents"
    private const val PREFS = "mirage_node_events"
    private const val KEY_PREFIX = "events_"
    private const val MAX_EVENTS = 100

    data class Event(
        val state: String,
        val at: Long,
        val detail: String? = null
    )

    @Synchronized
    fun record(context: Context, node: String, state: String, detail: String? = null) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = KEY_PREFIX + node
        val events = read(prefs.getString(key, null))
        if (events.lastOrNull()?.state == state) return

        val next = (events + Event(state, System.currentTimeMillis(), detail))
            .takeLast(MAX_EVENTS)
        prefs.edit().putString(key, encode(next)).apply()

        val tail = detail?.let { " ($it)" }.orEmpty()
        if (state == STATE_DOWN) {
            AppLogger.w(TAG, "${nodeLabel(node)}: зафиксирован сбой$tail")
        } else {
            AppLogger.i(TAG, "${nodeLabel(node)}: состояние $state$tail")
        }
    }

    @Synchronized
    fun history(context: Context, node: String, limit: Int = 5): List<Event> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return read(prefs.getString(KEY_PREFIX + node, null))
            .asReversed()
            .take(limit.coerceAtLeast(1))
    }

    private fun nodeLabel(node: String): String = when (node) {
        NODE_BELARUS -> "Беларусь"
        else -> node
    }

    private fun encode(events: List<Event>): String {
        val array = JSONArray()
        events.forEach { event ->
            array.put(JSONObject().apply {
                put("s", event.state)
                put("t", event.at)
                event.detail?.let { put("d", it) }
            })
        }
        return array.toString()
    }

    private fun read(raw: String?): List<Event> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Event>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val state = obj.optString("s", "").trim()
                if (state.isEmpty()) continue
                out += Event(
                    state = state,
                    at = obj.optLong("t", 0L),
                    detail = obj.optString("d").takeIf { it.isNotBlank() && it != "null" }
                )
            }
            out
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Не удалось прочитать историю состояний: ${t.message}")
            emptyList()
        }
    }
}
