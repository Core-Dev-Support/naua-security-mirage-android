package com.naua_security_mirage.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessServerTest {
    @Test
    fun parsesXhttpRealityParametersWithoutDroppingTransport() {
        val link = "vless://11111111-1111-1111-1111-111111111111@example.com:3443" +
            "?type=xhttp&security=reality&pbk=public-key&fp=chrome" +
            "&sni=cdn.example&sid=abcd&spx=%2F&path=%2FwidgetComponent.js" +
            "&host=cdn.example&mode=packet-up#Node"

        val server = VlessServer.fromUri(link)

        assertNotNull(server)
        assertEquals("xhttp", server!!.network)
        assertEquals("reality", server.security)
        assertEquals("/widgetComponent.js", server.path)
        assertEquals("cdn.example", server.host)
        assertEquals("packet-up", server.mode)
        assertEquals("abcd", server.shortId)
        assertTrue(server.toVlessUri().contains("path=%2FwidgetComponent.js"))
    }

    @Test
    fun parsesRealityTcpFlowAndSpiderPath() {
        val link = "vless://22222222-2222-2222-2222-222222222222@example.com:443" +
            "?type=tcp&security=reality&pbk=key&sni=example.org&sid=1234" +
            "&spx=%2F&flow=xtls-rprx-vision#France"

        val server = VlessServer.fromUri(link)

        assertNotNull(server)
        assertEquals("tcp", server!!.network)
        assertEquals("/", server.path)
        assertEquals("xtls-rprx-vision", server.flow)
        assertEquals("1234", server.shortId)
    }
}
