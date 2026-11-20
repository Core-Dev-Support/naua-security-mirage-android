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

    @Test
    fun parsesHysteria2UriAndSerializesCorrectly() {
        val link = "hysteria2://password123@example.com:8443?sni=hy2.example.com&insecure=1&obfs=salamander&obfs-password=mysecret#SwissNode"

        val server = VlessServer.fromUri(link)

        assertNotNull(server)
        assertEquals("hysteria2", server!!.protocol)
        assertEquals("password123", server.uuid)
        assertEquals("example.com", server.address)
        assertEquals(8443, server.port)
        assertEquals("hy2.example.com", server.serverName)
        assertEquals("hy2.example.com", server.host)
        assertTrue(server.isHysteria2)
        assertTrue(server.insecure)
        assertEquals("salamander", server.obfs)
        assertEquals("mysecret", server.obfsPassword)
        assertEquals("SwissNode", server.tag)

        val serialized = server.toVlessUri()
        assertTrue(serialized.startsWith("hysteria2://password123@example.com:8443"))
        assertTrue(serialized.contains("sni=hy2.example.com"))
        assertTrue(serialized.contains("insecure=1"))
        assertTrue(serialized.contains("obfs=salamander"))
        assertTrue(serialized.contains("obfs-password=mysecret"))
        assertTrue(serialized.endsWith("#SwissNode"))
    }

    @Test
    fun parsesHy2ShortAliasUri() {
        val link = "hy2://mypass@192.0.2.1:443?sni=cdn.example.org#ShortHy2"

        val server = VlessServer.fromUri(link)

        assertNotNull(server)
        assertEquals("hysteria2", server!!.protocol)
        assertTrue(server.isHysteria2)
        assertEquals("mypass", server.uuid)
        assertEquals("192.0.2.1", server.address)
        assertEquals(443, server.port)
        assertEquals("cdn.example.org", server.serverName)
        assertEquals("cdn.example.org", server.host)
        assertEquals("ShortHy2", server.tag)
    }
}
