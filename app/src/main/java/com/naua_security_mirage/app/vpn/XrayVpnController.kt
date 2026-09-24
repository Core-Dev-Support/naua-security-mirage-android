package com.naua_security_mirage.app.vpn

import android.net.VpnService
import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.data.repository.GeoRoutingRepository
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.util.AppLogger
import go.Seq
import libXray.DialerController
import libXray.LibXray
import java.io.File
import java.nio.charset.StandardCharsets

class XrayVpnController(private val vpnService: VpnService) {

    private var isStarted = false

    fun startXray(server: VlessServer, tunFd: Int): Pair<Boolean, String?> {
        try {
            AppLogger.i(TAG, "Ensuring previous Xray instance is stopped...")
            Log.d(TAG, "Ensuring previous Xray instance is stopped...")
            try {
                if (LibXray.getXrayState()) {
                    LibXray.stopXray()
                    Thread.sleep(50)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error checking/stopping prior Xray: ${e.message}")
            }

            Log.d(TAG, "Setting Go Seq context...")
            try {
                Seq.setContext(vpnService)
            } catch (e: Throwable) {
                Log.w(TAG, "Seq.setContext error: ${e.message}")
            }

            // Register DialerController so all outgoing TCP/UDP sockets created by LibXray
            // (including the connection to France proxy server) are protected with vpnService.protect()
            // to bypass the TUN interface and prevent infinite connection loops.
            Log.d(TAG, "Registering DialerController & ListenerController...")
            val controller = object : DialerController {
                override fun protectFd(fd: Long): Boolean {
                    return vpnService.protect(fd.toInt())
                }
            }
            try {
                LibXray.registerDialerController(controller)
                LibXray.registerListenerController(controller)
                Log.d(TAG, "DialerController registered successfully")
            } catch (e: Throwable) {
                Log.w(TAG, "registerDialerController failed: ${e.message}")
            }

            Log.d(TAG, "Setting TunFd: $tunFd")
            LibXray.setTunFd(tunFd)
            try {
                System.setProperty("xray.tun.fd", tunFd.toString())
            } catch (_: Throwable) {}

            val geoRoutingRepo = GeoRoutingRepository(vpnService)
            geoRoutingRepo.cleanupInvalidFiles()
            val geoDir = geoRoutingRepo.geoDir
            if (!geoDir.exists()) {
                geoDir.mkdirs()
            }
            val datDirPath = geoDir.absolutePath
            try {
                System.setProperty("xray.location.asset", datDirPath)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to set xray.location.asset: ${e.message}")
            }

            val configJson = buildManualConfig(server)
            Log.d(TAG, "Xray config generated for ${server.tag}")

            val request = JsonObject().apply {
                addProperty("datDir", datDirPath)
                addProperty("mphCachePath", "")
                addProperty("configJSON", configJson)
            }
            val base64Req = Base64.encodeToString(
                request.toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )

            Log.d(TAG, "Launching Xray via runXrayFromJSON...")
            val rawResult = LibXray.runXrayFromJSON(base64Req)
            Log.d(TAG, "runXrayFromJSON raw result: $rawResult")

            val decodedResult = try {
                if (!rawResult.isNullOrEmpty()) {
                    val bytes = Base64.decode(rawResult, Base64.DEFAULT)
                    String(bytes, StandardCharsets.UTF_8)
                } else ""
            } catch (_: Exception) {
                rawResult ?: ""
            }

            Log.d(TAG, "Decoded Xray run response: $decodedResult")

            val error = try {
                if (decodedResult.isNotEmpty()) {
                    val json = org.json.JSONObject(decodedResult)
                    if (json.has("error")) json.optString("error") else null
                } else null
            } catch (_: Exception) {
                if (decodedResult.contains("\"error\"")) decodedResult else null
            }

            var runningState = try { LibXray.getXrayState() } catch (_: Throwable) { false }
            if (!runningState && !explicitResultContainsSuccess(decodedResult)) {
                repeat(5) {
                    if (runningState) return@repeat
                    Thread.sleep(100)
                    runningState = try { LibXray.getXrayState() } catch (_: Throwable) { false }
                }
            }
            val explicitSuccess = explicitResultContainsSuccess(decodedResult)
            val isSuccess = runningState || (error.isNullOrEmpty() && explicitSuccess)

            if (!error.isNullOrEmpty()) {
                AppLogger.e(TAG, "Xray core start error: $error")
                Log.e(TAG, "Xray core start error: $error")
            }

            isStarted = isSuccess
            AppLogger.i(TAG, "Xray start status: isSuccess=$isSuccess (xrayState=$runningState)")
            Log.d(TAG, "Xray start status: isSuccess=$isSuccess (xrayState=$runningState)")
            return Pair(isSuccess, error)

        } catch (e: Throwable) {
            AppLogger.e(TAG, "Failed to start Xray: ${e.message}", e)
            Log.e(TAG, "Failed to start Xray: ${e.message}", e)
            return Pair(false, e.message)
        }
    }

    private fun explicitResultContainsSuccess(value: String): Boolean {
        return Regex("\\\"success\\\"\\s*:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(value)
    }

    fun stopXray() {
        try {
            val isRunning = try { LibXray.getXrayState() } catch (_: Throwable) { false }
            if (isStarted || isRunning) {
                AppLogger.i(TAG, "Stopping Xray core...")
                Log.d(TAG, "Stopping Xray core...")
                LibXray.stopXray()
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Error stopping Xray: ${e.message}", e)
            Log.e(TAG, "Error stopping Xray: ${e.message}")
        } finally {
            isStarted = false
        }
    }

    /**
     * Real data-plane health check: opens a SOCKS5 session through the local Xray inbound and
     * waits for an actual HTTP answer from a public endpoint.
     *
     * A successful VLESS handshake proves nothing on its own — a node can accept the tunnel and
     * then silently drop every byte (dead egress, wrong transport params, rejected client).
     * This probe is what separates "connected" from "actually working".
     */
    fun verifyDataPlane(timeoutMs: Int = 6000): Boolean {
        for ((host, port) in PROBE_TARGETS) {
            if (probeOnce(host, port, timeoutMs)) {
                AppLogger.i(TAG, "Проверка трафика пройдена через $host:$port")
                return true
            }
        }
        AppLogger.w(TAG, "Проверка трафика не пройдена: туннель поднят, но данные не проходят")
        return false
    }

    private fun probeOnce(host: String, port: Int, timeoutMs: Int): Boolean {
        var socket: java.net.Socket? = null
        return try {
            val ip = java.net.InetAddress.getByName(host).address
            if (ip.size != 4) return false

            socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", SOCKS_PORT), timeoutMs)
            socket.soTimeout = timeoutMs
            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            // SOCKS5 greeting: no authentication
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting, 2)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) return false

            // SOCKS5 CONNECT to the probe target
            val request = ByteArray(10)
            request[0] = 0x05
            request[1] = 0x01
            request[2] = 0x00
            request[3] = 0x01
            System.arraycopy(ip, 0, request, 4, 4)
            request[8] = ((port shr 8) and 0xFF).toByte()
            request[9] = (port and 0xFF).toByte()
            output.write(request)
            output.flush()

            val header = ByteArray(4)
            readFully(input, header, 4)
            if (header[0] != 0x05.toByte() || header[1] != 0x00.toByte()) return false
            val addrLen = when (header[3].toInt()) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> {
                    val len = ByteArray(1)
                    readFully(input, len, 1)
                    len[0].toInt() and 0xFF
                }
                else -> return false
            }
            readFully(input, ByteArray(addrLen + 2), addrLen + 2)

            output.write("HEAD / HTTP/1.0\r\nHost: one.one.one.one\r\n\r\n".toByteArray())
            output.flush()

            val buffer = ByteArray(16)
            val read = input.read(buffer)
            read > 0 && String(buffer, 0, read).startsWith("HTTP")
        } catch (t: Throwable) {
            Log.d(TAG, "Probe to $host:$port failed: ${t.message}")
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {}
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read <= 0) throw java.io.EOFException("probe stream closed")
            offset += read
        }
    }

    /**
     * Builds a VLESS share link that keeps every transport parameter the node needs.
     *
     * Reality TCP nodes use spiderX/flow; xhttp nodes use path/host/mode. Losing those
     * parameters (as happened before) makes the node silently accept the TCP connection
     * but never pass any data, which looks exactly like "the subscription is broken".
     */
    private fun buildShareLink(server: VlessServer): String {
        fun enc(value: String): String = try {
            java.net.URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }

        val network = server.network.ifEmpty { "tcp" }
        val security = server.security.ifEmpty { "reality" }

        val params = StringBuilder()
        params.append("type=").append(network)
        params.append("&encryption=none")
        params.append("&security=").append(security)

        if (security == "reality") {
            params.append("&pbk=").append(enc(server.publicKey))
            params.append("&fp=").append(enc(server.fingerprint.ifEmpty { "chrome" }))
            if (server.serverName.isNotEmpty()) {
                params.append("&sni=").append(enc(server.serverName))
            }
            if (server.shortId.isNotEmpty()) {
                params.append("&sid=").append(enc(server.shortId))
            }
        } else if (security == "tls") {
            params.append("&tls=1")
            if (server.serverName.isNotEmpty()) {
                params.append("&sni=").append(enc(server.serverName))
            }
        }

        when (network) {
            "xhttp" -> {
                params.append("&path=").append(enc(server.path.ifEmpty { "/widgetComponent.js" }))
                params.append("&host=").append(enc(server.host.ifEmpty { server.serverName }))
                params.append("&mode=").append(enc(server.mode.ifEmpty { "packet-up" }))
            }
            "tcp" -> {
                if (security == "reality") {
                    params.append("&spx=").append(enc(server.path.ifEmpty { "/" }))
                }
            }
            "ws" -> {
                params.append("&path=").append(enc(server.path.ifEmpty { "/" }))
                if (server.host.isNotEmpty()) {
                    params.append("&host=").append(enc(server.host))
                }
            }
        }

        if (server.flow.isNotBlank()) {
            params.append("&flow=").append(enc(server.flow))
        }

        val tag = try {
            java.net.URLEncoder.encode(server.tag.ifEmpty { "proxy" }, "UTF-8")
        } catch (_: Exception) {
            "proxy"
        }

        return "vless://${server.uuid}@${server.address}:${server.port}?$params#$tag"
    }

    private fun buildManualConfig(server: VlessServer): String {
        val root = JsonObject()
        val settingsRepo = SettingsRepository(vpnService)

        val cacheDir = vpnService.cacheDir.absolutePath
        val log = JsonObject().apply {
            addProperty("loglevel", "warning")
            addProperty("access", "$cacheDir/xray_access.log")
            addProperty("error", "$cacheDir/xray_error.log")
        }
        root.add("log", log)

        // Policy level 8 (identical to v2rayNG standard): sets connIdle and handshake timeout
        val policy = JsonObject().apply {
            val levels = JsonObject()
            val level8 = JsonObject().apply {
                addProperty("handshake", 8)
                addProperty("connIdle", 300)
            }
            levels.add("8", level8)
            val level0 = JsonObject().apply {
                addProperty("handshake", 8)
                addProperty("connIdle", 300)
            }
            levels.add("0", level0)
            add("levels", levels)
            val system = JsonObject().apply {
                addProperty("statsOutboundUplink", true)
                addProperty("statsOutboundDownlink", true)
            }
            add("system", system)
        }
        root.add("policy", policy)

        val isDirectRu = settingsRepo.isDirectRuEnabled
        val geoDir = File(vpnService.filesDir, "geo")
        val geositeFile = File(geoDir, "geosite.dat")
        val geoipFile = File(geoDir, "geoip.dat")
        val hasGeoSite = geositeFile.exists() && geositeFile.length() > 100_000 && GeoRoutingRepository.hasCategoryRu(geositeFile)
        val hasGeoIp = geoipFile.exists() && geoipFile.length() > 500_000

        // DNS configuration matching v2rayNG / Happ:
        // Fast UDP/TCP DNS over proxy for remote domains, and direct Yandex DNS for Russian domains.
        val dns = JsonObject().apply {
            val hosts = JsonObject().apply {
                val cfIps = JsonArray().apply { add("1.1.1.1"); add("1.0.0.1") }
                add("cloudflare-dns.com", cfIps)
                add("one.one.one.one", cfIps)
                add("1dot1dot1dot1.cloudflare-dns.com", cfIps)
                val cfDnsComIps = JsonArray().apply { add("162.159.61.8"); add("172.64.41.8") }
                add("dns.cloudflare.com", cfDnsComIps)
                val googleIps = JsonArray().apply { add("8.8.8.8"); add("8.8.4.4") }
                add("dns.google", googleIps)
                val yandexIps = JsonArray().apply { add("77.88.8.8"); add("77.88.8.1") }
                add("common.dot.dns.yandex.net", yandexIps)
            }
            add("hosts", hosts)

            val servers = JsonArray().apply {
                // Fast TCP DNS over proxy as primary (instant resolution, immune to mobile UDP filtering/timeouts)
                add("tcp://1.1.1.1:53")
                add("tcp://8.8.8.8:53")
                add("1.1.1.1")
                add("8.8.8.8")

                val directDns = JsonObject().apply {
                    addProperty("address", "77.88.8.8")
                    addProperty("port", 53)
                    val domains = JsonArray().apply {
                        if (hasGeoSite) {
                            add("geosite:category-ru")
                            add("geosite:tld-ru")
                        }
                        add("domain:ru")
                        add("domain:su")
                        add("domain:xn--p1ai")
                        add("domain:рф")
                        add("domain:yandex")
                        add("domain:ya.ru")
                        add("domain:vk.com")
                        add("domain:mail.ru")
                        add("domain:gosuslugi.ru")
                        add("domain:sberbank.ru")
                        add("domain:sber.ru")
                        add("domain:tbank.ru")
                        add("domain:tinkoff.ru")
                        add("domain:ozon.ru")
                        add("domain:wildberries.ru")
                        add("domain:avito.ru")
                        add("domain:rutube.ru")
                    }
                    add("domains", domains)
                    addProperty("skipFallback", true)
                }
                add(directDns)
            }
            add("servers", servers)
            addProperty("queryStrategy", "UseIPv4")
            addProperty("enableParallelQuery", false)
            // Short DNS cache: apps retry the same names constantly, cached answers avoid
            // a fresh proxy round trip for every retry.
            addProperty("disableCache", false)
        }
        root.add("dns", dns)

        // inbounds: socks (10808) + tun (xray0) with uppercase MTU, userLevel 8 and routeOnly=true
        val inbounds = JsonArray()

        val socksInbound = JsonObject().apply {
            addProperty("tag", "socks")
            addProperty("port", 10808)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "socks")
            val settings = JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
                addProperty("userLevel", 8)
            }
            add("settings", settings)
            val sniffing = JsonObject().apply {
                addProperty("enabled", true)
                val destOverride = JsonArray().apply {
                    add("http")
                    add("tls")
                    add("quic")
                }
                add("destOverride", destOverride)
                addProperty("routeOnly", true)
            }
            add("sniffing", sniffing)
        }
        inbounds.add(socksInbound)

        val tunInbound = JsonObject().apply {
            addProperty("tag", "tun")
            addProperty("protocol", "tun")
            val settings = JsonObject().apply {
                addProperty("name", "xray0")
                addProperty("MTU", 1500)
                addProperty("userLevel", 8)
            }
            add("settings", settings)
            val sniffing = JsonObject().apply {
                addProperty("enabled", true)
                val destOverride = JsonArray().apply {
                    add("http")
                    add("tls")
                    add("quic")
                }
                add("destOverride", destOverride)
                addProperty("routeOnly", true)
            }
            add("sniffing", sniffing)
        }
        inbounds.add(tunInbound)

        root.add("inbounds", inbounds)

        // outbounds: vless proxy + freedom direct + blackhole block + dns
        val outbounds = JsonArray()

        val vlessOutbound = JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "vless")

            val settings = JsonObject()
            val vnext = JsonArray()
            val node = JsonObject().apply {
                addProperty("address", server.address)
                addProperty("port", server.port)
                val users = JsonArray()
                val user = JsonObject().apply {
                    addProperty("id", server.uuid)
                    addProperty("encryption", "none")
                    if (server.flow.isNotEmpty()) {
                        addProperty("flow", server.flow)
                    }
                    addProperty("level", 8)
                }
                users.add(user)
                add("users", users)
            }
            vnext.add(node)
            settings.add("vnext", vnext)
            add("settings", settings)

            val network = server.network.ifEmpty { "tcp" }
            val security = server.security.ifEmpty { "reality" }

            val streamSettings = JsonObject().apply {
                addProperty("network", network)
                addProperty("security", security)

                if (security == "reality") {
                    val realitySettings = JsonObject().apply {
                        addProperty("publicKey", server.publicKey)
                        addProperty("serverName", server.serverName)
                        addProperty("fingerprint", server.fingerprint.ifEmpty { "chrome" })
                        if (server.shortId.isNotEmpty()) {
                            addProperty("shortId", server.shortId)
                        }
                        // spiderX belongs to the Reality TCP transport only — sending it for
                        // xhttp nodes breaks the handshake.
                        if (network == "tcp") {
                            addProperty("spiderX", server.path.ifEmpty { "/" })
                        }
                    }
                    add("realitySettings", realitySettings)
                }

                if (network == "xhttp") {
                    val xhttpSettings = JsonObject().apply {
                        addProperty("host", server.host.ifEmpty { server.serverName })
                        addProperty("mode", server.mode.ifEmpty { "packet-up" })
                        addProperty("path", server.path.ifEmpty { "/widgetComponent.js" })
                    }
                    add("xhttpSettings", xhttpSettings)
                }

                if (network == "tcp") {
                    val tcpSettings = JsonObject().apply {
                        val header = JsonObject().apply {
                            addProperty("type", "none")
                        }
                        add("header", header)
                    }
                    add("tcpSettings", tcpSettings)
                }

                val sockopt = JsonObject().apply {
                    addProperty("domainStrategy", "UseIP")
                }
                add("sockopt", sockopt)
            }
            add("streamSettings", streamSettings)
        }

        // Generate official outbound using LibXray native link parser (matches v2rayNG / Happ exactly)
        var officialOutbound: JsonObject? = null
        try {
            val vlessLink = buildShareLink(server)

            val linkB64 = Base64.encodeToString(vlessLink.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            val rawConverted = LibXray.convertShareLinksToXrayJson(linkB64)
            val decodedStr = if (!rawConverted.isNullOrEmpty()) {
                try {
                    String(Base64.decode(rawConverted, Base64.DEFAULT), StandardCharsets.UTF_8)
                } catch (_: Exception) {
                    rawConverted
                }
            } else ""

            Log.d(TAG, "Decoded convertShareLinksToXrayJson response: $decodedStr")
            if (decodedStr.isNotEmpty()) {
                val jsonRoot = JsonParser.parseString(decodedStr).asJsonObject
                val xrayConfigObj = XrayEnvelope.unwrap(jsonRoot)
                if (xrayConfigObj.has("outbounds")) {
                    val obs = xrayConfigObj.getAsJsonArray("outbounds")
                    if (obs.size() > 0) {
                        val ob = obs.get(0).asJsonObject
                        ob.addProperty("tag", "proxy")

                        // Ensure level 8 on users in official outbound
                        try {
                            val obSettings = ob.getAsJsonObject("settings")
                            if (obSettings != null && obSettings.has("vnext")) {
                                val vnextArr = obSettings.getAsJsonArray("vnext")
                                for (nodeElem in vnextArr) {
                                    val nodeObj = nodeElem.asJsonObject
                                    if (nodeObj.has("users")) {
                                        val usersArr = nodeObj.getAsJsonArray("users")
                                        for (userElem in usersArr) {
                                            userElem.asJsonObject.addProperty("level", 8)
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {}

                        // Ensure sockopt on streamSettings
                        try {
                            val ss = if (ob.has("streamSettings")) ob.getAsJsonObject("streamSettings") else JsonObject().also { ob.add("streamSettings", it) }
                            val sockopt = JsonObject().apply {
                                addProperty("domainStrategy", "UseIP")
                            }
                            ss.add("sockopt", sockopt)
                        } catch (_: Throwable) {}

                        officialOutbound = ob
                        AppLogger.i(TAG, "Using official LibXray outbound: ${ob.get("protocol")?.asString}")
                        Log.d(TAG, "Using official LibXray outbound: $ob")
                    }
                }
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "LibXray.convertShareLinksToXrayJson fallback to manual: ${e.message}")
            Log.w(TAG, "LibXray.convertShareLinksToXrayJson fallback to manual: ${e.message}")
        }

        // Only trust the LibXray-converted outbound when it kept the transport parameters
        // that the transport actually needs (xhttp path/host/mode, Reality keys).
        val usableOfficial = officialOutbound?.takeIf { outbound ->
            val stream = outbound.getAsJsonObject("streamSettings")
            val network = stream?.get("network")?.asString.orEmpty()
            when (network) {
                "xhttp" -> {
                    val xhttp = stream?.getAsJsonObject("xhttpSettings")
                    val hostValue = xhttp?.get("host")
                    val host = if (hostValue == null) null else {
                        if (hostValue.isJsonArray) hostValue.asJsonArray.firstOrNull()?.asString else hostValue.asString
                    }
                    xhttp != null &&
                        !xhttp.get("path")?.asString.isNullOrEmpty() &&
                        !host.isNullOrEmpty() &&
                        !xhttp.get("mode")?.asString.isNullOrEmpty()
                }
                "tcp" -> {
                    if (server.security.ifEmpty { "reality" } != "reality") true
                    else {
                        val reality = stream?.getAsJsonObject("realitySettings")
                        !reality?.get("publicKey")?.asString.isNullOrEmpty() &&
                            !reality?.get("serverName")?.asString.isNullOrEmpty() &&
                            !reality?.get("shortId")?.asString.isNullOrEmpty()
                    }
                }
                else -> true
            }
        }
        if (usableOfficial == null && officialOutbound != null) {
            AppLogger.w(TAG, "LibXray outbound lost transport params for ${server.tag} — using the manual outbound")
            Log.w(TAG, "LibXray outbound lost transport params for ${server.tag} — using the manual outbound")
        }
        outbounds.add(usableOfficial ?: vlessOutbound)

        val directOutbound = JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
            val streamSettings = JsonObject().apply {
                val sockopt = JsonObject().apply {
                    addProperty("domainStrategy", "UseIP")
                }
                add("sockopt", sockopt)
            }
            add("streamSettings", streamSettings)
        }
        outbounds.add(directOutbound)

        // Block outbound for dropping problematic UDP 443 (QUIC/HTTP3)
        val blockOutbound = JsonObject().apply {
            addProperty("tag", "block")
            addProperty("protocol", "blackhole")
            val settings = JsonObject().apply {
                val response = JsonObject().apply {
                    addProperty("type", "none")
                }
                add("response", response)
            }
            add("settings", settings)
        }
        outbounds.add(blockOutbound)

        val dnsOutbound = JsonObject().apply {
            addProperty("tag", "dns-out")
            addProperty("protocol", "dns")
        }
        outbounds.add(dnsOutbound)

        root.add("outbounds", outbounds)

        // routing
        val routing = JsonObject().apply {
            addProperty("domainStrategy", "IPIfNonMatch")
            val rules = JsonArray()

            // 0. Intercept DNS (port 53 UDP & TCP) into Xray internal DNS resolver
            val dnsRule = JsonObject().apply {
                addProperty("type", "field")
                val inboundsList = JsonArray().apply { add("tun"); add("socks") }
                add("inboundTag", inboundsList)
                addProperty("port", "53")
                addProperty("outboundTag", "dns-out")
            }
            rules.add(dnsRule)

            // 1. Direct Russian DNS queries (77.88.8.8) route directly
            val directDnsRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", "direct")
                val ips = JsonArray().apply { add("77.88.8.8"); add("77.88.8.1") }
                add("ip", ips)
                addProperty("port", "53")
            }
            rules.add(directDnsRule)

            // 2. Remote DNS queries (1.1.1.1, 8.8.8.8) route through proxy
            val proxyDnsRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", "proxy")
                val ips = JsonArray().apply { add("1.1.1.1"); add("8.8.8.8") }
                add("ip", ips)
                addProperty("port", "53")
            }
            rules.add(proxyDnsRule)

            // 2.1 Android Private DNS (DoT - port 853): route through proxy so system network validation passes
            val dotRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("port", "853")
                addProperty("outboundTag", "proxy")
            }
            rules.add(dotRule)

            // 3. Anti-loop: Ensure server IP always routes directly
            if (server.address.isNotEmpty()) {
                val serverDirectRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val ips = JsonArray().apply { add(server.address) }
                    add("ip", ips)
                }
                rules.add(serverDirectRule)
            }

            // 4. Custom Bypassed Websites (User-defined exceptions routed directly without VPN)
            val customBypassedDomains = settingsRepo.getActiveBypassedDomains()
            if (customBypassedDomains.isNotEmpty()) {
                val customDomainRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val domains = JsonArray()
                    customBypassedDomains.forEach { domain ->
                        domains.add("domain:$domain")
                    }
                    add("domain", domains)
                }
                rules.add(customDomainRule)
                AppLogger.i(TAG, "Раздельное туннелирование сайтов: добавлено пользовательских доменов в исключения: ${customBypassedDomains.size}")
            }

            // 5. Block QUIC (UDP 443): forces browser & YouTube app to use TCP TLS 1.3 Vision
            val blockQuicRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("port", "443")
                addProperty("network", "udp")
                addProperty("outboundTag", "block")
            }
            rules.add(blockQuicRule)

            if (isDirectRu) {
                // 6. Direct Russian Domains (TOP PRIORITY: bypasses proxy immediately for Russian websites)
                val directDomainRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")

                    val domains = JsonArray().apply {
                        if (hasGeoSite) {
                            add("geosite:category-ru")
                            add("geosite:tld-ru")
                        }
                        // Built-in standard Russian services and CDNs
                        add("domain:yandex")
                        add("domain:ya.ru")
                        add("domain:yastatic.net")
                        add("domain:yandex.net")
                        add("domain:dzen.ru")
                        add("domain:dzeninfra.ru")
                        add("domain:kinopoisk.ru")
                        add("domain:vk.com")
                        add("domain:vk-cdn.net")
                        add("domain:userapi.com")
                        add("domain:mail.ru")
                        add("domain:ok.ru")
                        add("domain:gosuslugi.ru")
                        add("domain:sberbank.ru")
                        add("domain:sber.ru")
                        add("domain:tbank.ru")
                        add("domain:tinkoff.ru")
                        add("domain:ozon.ru")
                        add("domain:wildberries.ru")
                        add("domain:avito.ru")
                        add("domain:avito.st")
                        add("domain:rutube.ru")
                        add("domain:ru")
                        add("domain:su")
                        add("domain:xn--p1ai")
                        add("domain:рф")
                        add("regexp:.*\\.ru$")
                        add("regexp:.*\\.su$")
                        add("regexp:.*\\.xn--p1ai$")
                        add("regexp:.*\\.рф$")
                    }
                    add("domain", domains)
                }
                rules.add(directDomainRule)

                // 7. Direct Russian & Private IP addresses (SECOND PRIORITY: direct connection for RU subnets & local LAN)
                val directIpRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")

                    val ips = JsonArray().apply {
                        // Direct Russian DNS (zero latency for Russian sites)
                        add("77.88.8.8")
                        add("77.88.8.1")
                        add("77.88.8.88")
                        add("77.88.8.2")

                        // Private & CGNAT ranges
                        add("10.0.0.0/8")
                        add("172.16.0.0/12")
                        add("192.168.0.0/16")
                        add("127.0.0.0/8")
                        add("100.64.0.0/10")

                        // Core Russian subnets (Yandex, VK)
                        add("77.88.0.0/18")
                        add("87.250.248.0/21")
                        add("5.255.240.0/20")
                        add("213.180.192.0/19")
                        add("93.158.128.0/18")
                        add("178.154.128.0/18")
                        add("87.240.128.0/18")
                        add("93.186.224.0/20")
                        add("95.213.0.0/17")

                        // Smart DNS & SNI proxy ranges
                        add("111.88.96.0/24")
                        add("87.228.47.0/24")
                        add("92.223.109.0/24")

                        if (hasGeoIp) {
                            add("geoip:ru")
                            add("geoip:private")
                        }
                    }
                    add("ip", ips)
                }
                rules.add(directIpRule)
            }

            // 8. Route all remaining TCP/UDP traffic to proxy (global Internet & blocked sites)
            val proxyRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "tcp,udp")
                addProperty("outboundTag", "proxy")
            }
            rules.add(proxyRule)

            add("rules", rules)
        }
        root.add("routing", routing)

        return root.toString()
    }

    companion object {
        private const val TAG = "XrayVpnController"

        /** Local SOCKS inbound of the generated Xray config, used for health checks. */
        private const val SOCKS_PORT = 10808

        /** IP-literal endpoints (no local DNS needed) that answer plain HTTP. */
        private val PROBE_TARGETS = listOf(
            "1.1.1.1" to 80,
            "1.0.0.1" to 80,
            "9.9.9.9" to 80
        )
    }
}
