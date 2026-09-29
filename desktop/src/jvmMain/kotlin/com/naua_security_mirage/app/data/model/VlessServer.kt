package com.naua_security_mirage.app.data.model

import java.net.URLEncoder

data class VlessServer(
    val id: String,
    val tag: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val network: String = "xhttp",
    val security: String = "reality",
    val publicKey: String,
    val fingerprint: String = "edge",
    val serverName: String,
    val host: String = serverName,
    val mode: String = "packet-up",
    val path: String = "/widgetComponent.js",
    val shortId: String = "",
    val flow: String = "",
    var pingMs: Long = -1L
) {
    fun toVlessUri(): String {
        val queryParams = mutableListOf(
            "type=$network",
            "security=$security",
            "pbk=$publicKey",
            "fp=$fingerprint",
            "sni=$serverName",
            "host=$host",
            "mode=$mode",
            "path=" + URLEncoder.encode(path, "UTF-8")
        )
        if (shortId.isNotEmpty()) {
            queryParams.add("sid=$shortId")
        }
        val query = queryParams.joinToString("&")
        val encodedTag = URLEncoder.encode(tag, "UTF-8")
        return "vless://$uuid@$address:$port?$query#$encodedTag"
    }
}
