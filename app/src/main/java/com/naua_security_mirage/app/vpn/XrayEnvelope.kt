package com.naua_security_mirage.app.vpn

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Normalizes the response envelopes emitted by different LibXray builds. */
object XrayEnvelope {
    fun unwrap(root: JsonObject): JsonObject {
        if (root.has("success") && !root.get("success").asBoolean) {
            throw IllegalArgumentException("LibXray conversion returned success=false")
        }

        fun parse(element: JsonElement): JsonObject {
            return if (element.isJsonObject) {
                element.asJsonObject
            } else {
                JsonParser.parseString(element.asString).asJsonObject
            }
        }

        val envelope = when {
            root.has("obj") -> root.get("obj")
            root.has("data") -> root.get("data")
            else -> root
        }
        var config = parse(envelope)
        if (!config.has("outbounds") && config.has("config")) {
            config = parse(config.get("config"))
        }
        return config
    }
}
