package com.naua_security_mirage.app.data.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parses the public `app-config` response without Gson's reflective mapping.
 *
 * Reflection-based DTOs silently lost their `@SerializedName` keys in the R8
 * release build, so the app fell back to built-in prices instead of the ones
 * served from Supabase. Reading the JSON tree with explicit keys keeps the
 * release build identical to debug and makes a missing field obvious.
 */
internal fun parseAppConfig(raw: String, fetchedAt: Long): AppConfig? {
    val root = runCatching { JsonParser.parseString(raw) }
        .getOrNull()
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?: return null

    val planSource = root.arrayOrNull("plans") ?: return null
    val seenPlans = HashSet<String>()
    val parsedPlans = ArrayList<SubscriptionPlan>(planSource.size())
    for (element in planSource) {
        if (!element.isJsonObject) continue
        val item = element.asJsonObject
        val id = item.stringOrNull("id") ?: continue
        val days = item.intOrNull("days") ?: 0
        val price = item.intOrNull("price_rub") ?: 0
        if (days !in 1..3650 || price !in 1..100_000) continue
        if (!seenPlans.add(id)) continue
        parsedPlans += SubscriptionPlan(
            id = id,
            days = days,
            priceRub = price,
            badge = item.stringOrNull("badge"),
            labelRu = item.stringOrNull("label_ru") ?: "$days дней",
            labelEn = item.stringOrNull("label_en") ?: "$days days"
        )
    }
    if (parsedPlans.isEmpty()) return null
    parsedPlans.sortWith(compareBy({ it.days }, { it.priceRub }))

    // Absent `paid_nodes` means "use the built-in catalogue". An empty array is
    // a deliberate "no paid nodes" state and must stay empty.
    val nodeSource = root.arrayOrNull("paid_nodes")
    val parsedNodes: List<PaidNode> = if (nodeSource == null) {
        AppConfig.DEFAULT_NODES
    } else {
        val seenNodes = HashSet<String>()
        val list = ArrayList<PaidNode>(nodeSource.size())
        for (element in nodeSource) {
            if (!element.isJsonObject) continue
            val item = element.asJsonObject
            val id = item.stringOrNull("id") ?: continue
            val configFunction = item.stringOrNull("config_function") ?: continue
            if (!seenNodes.add(id)) continue
            list += PaidNode(
                id = id,
                kind = PaidNode.Kind.from(item.stringOrNull("kind")),
                labelRu = item.stringOrNull("label_ru") ?: id,
                labelEn = item.stringOrNull("label_en") ?: id,
                flag = item.stringOrNull("flag") ?: "generic",
                configFunction = configFunction,
                sort = item.intOrNull("sort") ?: 100
            )
        }
        list.sortedWith(compareBy({ it.sort }, { it.id }))
    }

    return AppConfig(
        plans = parsedPlans,
        paidNodes = parsedNodes,
        minPriceRub = parsedPlans.minOf { it.priceRub },
        version = root.stringOrNull("version"),
        fetchedAt = fetchedAt
    )
}

internal fun isAppConfigFallback(raw: String): Boolean = runCatching {
    JsonParser.parseString(raw)
        .takeIf { it.isJsonObject }
        ?.asJsonObject
        ?.boolOrNull("fallback") == true
}.getOrDefault(false)

private fun JsonObject.arrayOrNull(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonObject.intOrNull(key: String): Int? =
    get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.let { runCatching { it.asInt }.getOrNull() }

private fun JsonObject.boolOrNull(key: String): Boolean? =
    get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.let { runCatching { it.asBoolean }.getOrNull() }
