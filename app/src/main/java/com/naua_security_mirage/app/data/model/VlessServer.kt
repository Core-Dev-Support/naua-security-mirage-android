package com.naua_security_mirage.app.data.model

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class VlessServer(
    val id: String,
    val tag: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val network: String = "tcp",
    val security: String = "reality",
    val publicKey: String,
    val fingerprint: String = "chrome",
    val serverName: String,
    val host: String = serverName,
    val mode: String = "none",
    val path: String = "/",
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
            "sni=$serverName"
        )
        if (host.isNotEmpty() && host != serverName) {
            queryParams.add("host=$host")
        }
        if (mode.isNotEmpty() && mode != "none") {
            queryParams.add("mode=$mode")
        }
        if (network == "tcp" && security == "reality" && path.isNotEmpty()) {
            queryParams.add("spx=" + URLEncoder.encode(path, "UTF-8"))
        } else if (network != "tcp" && path.isNotEmpty()) {
            queryParams.add("path=" + URLEncoder.encode(path, "UTF-8"))
        }
        if (shortId.isNotEmpty()) {
            queryParams.add("sid=$shortId")
        }
        if (flow.isNotEmpty()) {
            queryParams.add("flow=$flow")
        }
        val query = queryParams.joinToString("&")
        val encodedTag = URLEncoder.encode(tag, "UTF-8")
        return "vless://$uuid@$address:$port?$query#$encodedTag"
    }

    companion object {
        fun fromUri(uriString: String, customTag: String? = null): VlessServer? {
            return try {
                if (!uriString.startsWith("vless://")) return null
                val raw = uriString.trim()
                val uri = URI(raw)

                val uuid = uri.userInfo ?: raw.substringAfter("vless://").substringBefore("@")
                val host = uri.host ?: raw.substringAfter("@").substringBefore(":").substringBefore("?")
                val port = if (uri.port > 0) uri.port else 443

                val rawQuery = uri.rawQuery ?: raw.substringAfter("?", "").substringBefore("#")
                val params = mutableMapOf<String, String>()
                if (rawQuery.isNotEmpty()) {
                    rawQuery.split("&").forEach { param ->
                        val parts = param.split("=", limit = 2)
                        if (parts.size == 2) {
                            params[parts[0].lowercase()] = try {
                                URLDecoder.decode(parts[1], "UTF-8")
                            } catch (_: Exception) {
                                parts[1]
                            }
                        }
                    }
                }

                val fragment = uri.fragment ?: if (raw.contains("#")) raw.substringAfter("#") else null
                val tag = customTag ?: (if (!fragment.isNullOrBlank()) {
                    try { URLDecoder.decode(fragment, "UTF-8") } catch (_: Exception) { fragment }
                } else "Mirage Server")

                val network = params["type"] ?: params["net"] ?: "tcp"
                val security = params["security"] ?: "reality"
                val pbk = params["pbk"] ?: ""
                val fp = params["fp"] ?: "chrome"
                val sni = params["sni"] ?: params["host"] ?: ""
                val sid = params["sid"] ?: ""
                val spx = params["spx"] ?: params["path"] ?: "/"
                val flow = params["flow"] ?: ""
                val mode = params["mode"] ?: "none"

                VlessServer(
                    id = "vless-${uuid.take(8)}",
                    tag = tag,
                    address = host,
                    port = port,
                    uuid = uuid,
                    network = network,
                    security = security,
                    publicKey = pbk,
                    fingerprint = fp,
                    serverName = sni,
                    host = params["host"] ?: sni,
                    mode = mode,
                    path = if (network.equals("tcp", ignoreCase = true)) spx else (params["path"] ?: spx),
                    shortId = sid,
                    flow = flow
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
