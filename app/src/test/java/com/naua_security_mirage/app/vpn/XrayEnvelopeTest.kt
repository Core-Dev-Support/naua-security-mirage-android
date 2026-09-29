package com.naua_security_mirage.app.vpn

import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayEnvelopeTest {
    @Test
    fun unwrapsLegacyObjectEnvelope() {
        val root = JsonParser.parseString(
            "{\"success\":true,\"obj\":\"{\\\"outbounds\\\":[{\\\"protocol\\\":\\\"freedom\\\"}]}\"}"
        ).asJsonObject

        val config = XrayEnvelope.unwrap(root)

        assertTrue(config.has("outbounds"))
    }

    @Test
    fun unwrapsDataObjectEnvelope() {
        val root = JsonParser.parseString(
            "{\"success\":true,\"data\":{\"outbounds\":[{\"protocol\":\"freedom\"}]}}"
        ).asJsonObject

        val config = XrayEnvelope.unwrap(root)

        assertTrue(config.has("outbounds"))
    }

    @Test
    fun unwrapsNestedConfigEnvelope() {
        val root = JsonParser.parseString(
            "{\"success\":true,\"data\":{\"config\":\"{\\\"outbounds\\\":[]}\"}}"
        ).asJsonObject

        val config = XrayEnvelope.unwrap(root)

        assertTrue(config.has("outbounds"))
    }
}
