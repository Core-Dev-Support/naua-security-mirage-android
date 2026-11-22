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
    val publicKey: String = "",
    val fingerprint: String = "chrome",
    val serverName: String = "",
    val host: String = serverName,
    val mode: String = "none",
    val path: String = "/",
    val shortId: String = "",
    val flow: String = "",
    val protocol: String = "vless",
    val insecure: Boolean = false,
    val obfs: String = "",
    val obfsPassword: String = "",
    val pinSha256: String = "",
    var pingMs: Long = -1L,
    var lossPercent: Int = -1,
    var jitterMs: Long = -1L
) {
    val isHysteria2: Boolean
        get() = protocol.equals("hysteria2", ignoreCase = true) ||
            protocol.equals("hy2", ignoreCase = true)

    val isXhttp: Boolean
        get() = network.equals("xhttp", ignoreCase = true) ||
            network.equals("splithttp", ignoreCase = true)

    fun toVlessUri(): String = toShareUri()

    fun toShareUri(): String {
        val encodedTag = URLEncoder.encode(tag, "UTF-8")
        if (isHysteria2) {
            val queryParams = mutableListOf<String>()
            if (serverName.isNotEmpty()) {
                queryParams.add("sni=" + URLEncoder.encode(serverName, "UTF-8"))
            }
            if (insecure) {
                queryParams.add("insecure=1")
            }
            if (pinSha256.isNotEmpty()) {
                queryParams.add("pinSHA256=" + URLEncoder.encode(pinSha256, "UTF-8"))
            }
            if (obfs.isNotEmpty()) {
                queryParams.add("obfs=" + URLEncoder.encode(obfs, "UTF-8"))
                if (obfsPassword.isNotEmpty()) {
                    queryParams.add("obfs-password=" + URLEncoder.encode(obfsPassword, "UTF-8"))
                }
            }
            val query = if (queryParams.isNotEmpty()) "?" + queryParams.joinToString("&") else ""
            return "hysteria2://$uuid@$address:$port$query#$encodedTag"
        }

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
        return "vless://$uuid@$address:$port?$query#$encodedTag"
    }

    companion object {
        private fun parseHostAndPort(rawHostPort: String): Pair<String, Int> {
            val trimmed = rawHostPort.trim()
            if (trimmed.startsWith("[")) {
                val endBracket = trimmed.indexOf(']')
                if (endBracket != -1) {
                    val ipv6Host = trimmed.substring(1, endBracket)
                    val rest = trimmed.substring(endBracket + 1)
                    val port = if (rest.startsWith(":")) {
                        rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
                    } else 443
                    return Pair(ipv6Host, port)
                }
            }
            val lastColon = trimmed.lastIndexOf(':')
            return if (lastColon != -1 && trimmed.indexOf(':') == lastColon) {
                val host = trimmed.substring(0, lastColon)
                val port = trimmed.substring(lastColon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
                Pair(host, port)
            } else {
                Pair(trimmed, 443)
            }
        }

        fun fromUri(uriString: String, customTag: String? = null): VlessServer? {
            return try {
                val raw = uriString.trim()
                val isHy2 = raw.startsWith("hysteria2://", ignoreCase = true) ||
                    raw.startsWith("hy2://", ignoreCase = true)
                val isVless = raw.startsWith("vless://", ignoreCase = true)
                if (!isHy2 && !isVless) return null

                val scheme = if (isHy2) "hysteria2" else "vless"
                val prefix = if (isHy2) {
                    if (raw.startsWith("hy2://", ignoreCase = true)) "hy2://" else "hysteria2://"
                } else "vless://"

                val afterScheme = raw.substring(prefix.length)
                val userInfo = if (afterScheme.contains("@")) afterScheme.substringBefore("@") else ""
                val afterUser = if (afterScheme.contains("@")) afterScheme.substringAfter("@") else afterScheme
                val hostPort = afterUser.substringBefore("?").substringBefore("#")
                val (host, port) = parseHostAndPort(hostPort)

                val rawQuery = if (raw.contains("?")) raw.substringAfter("?").substringBefore("#") else ""
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

                val fragment = if (raw.contains("#")) raw.substringAfter("#") else null
                val tag = customTag ?: (if (!fragment.isNullOrBlank()) {
                    try { URLDecoder.decode(fragment, "UTF-8") } catch (_: Exception) { fragment }
                } else "Mirage Server")

                if (isHy2) {
                    val auth = if (userInfo.isNotEmpty()) userInfo else (params["auth"] ?: "")
                    val sni = params["sni"] ?: params["host"] ?: host
                    val insecure = params["insecure"] == "1" || params["allowinsecure"] == "1"
                    val obfs = params["obfs"] ?: ""
                    val obfsPassword = params["obfs-password"] ?: params["obfspassword"] ?: ""
                    val pinSha256 = params["pinsha256"] ?: params["pcs"] ?: ""

                    return VlessServer(
                        id = "hy2-${auth.take(8).ifEmpty { host.take(8) }}",
                        tag = tag,
                        address = host,
                        port = port,
                        uuid = auth,
                        network = "hysteria",
                        security = "tls",
                        publicKey = "",
                        fingerprint = params["fp"] ?: "chrome",
                        serverName = sni,
                        host = sni,
                        mode = "none",
                        path = "/",
                        shortId = "",
                        flow = "",
                        protocol = "hysteria2",
                        insecure = insecure,
                        obfs = obfs,
                        obfsPassword = obfsPassword,
                        pinSha256 = pinSha256
                    )
                }

                val rawNetwork = params["type"] ?: params["net"] ?: "tcp"
                val network = if (rawNetwork.equals("splithttp", ignoreCase = true)) "xhttp" else rawNetwork
                val security = params["security"] ?: "reality"
                val pbk = params["pbk"] ?: ""
                val fp = params["fp"] ?: "chrome"
                val sni = params["sni"] ?: params["host"] ?: ""
                val sid = params["sid"] ?: ""
                val spx = params["spx"] ?: params["path"] ?: "/"
                val flow = params["flow"] ?: ""
                val mode = params["mode"] ?: "none"

                VlessServer(
                    id = "$scheme-${userInfo.take(8).ifEmpty { host.take(8) }}",
                    tag = tag,
                    address = host,
                    port = port,
                    uuid = userInfo,
                    network = network,
                    security = security,
                    publicKey = pbk,
                    fingerprint = fp,
                    serverName = sni,
                    host = params["host"] ?: sni,
                    mode = mode,
                    path = if (network.equals("tcp", ignoreCase = true)) spx else (params["path"] ?: spx),
                    shortId = sid,
                    flow = flow,
                    protocol = scheme
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
