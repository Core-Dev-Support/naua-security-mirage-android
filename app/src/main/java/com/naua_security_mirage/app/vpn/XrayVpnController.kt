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

            Log.d(TAG, "Registering DialerController & ListenerController for socket protection...")
            val controller = object : DialerController {
                override fun protectFd(fd: Long): Boolean {
                    val res = vpnService.protect(fd.toInt())
                    Log.d(TAG, "protectFd($fd) -> $res")
                    return res
                }
            }
            try {
                LibXray.registerDialerController(controller)
                LibXray.registerListenerController(controller)
            } catch (e: Throwable) {
                Log.w(TAG, "registerDialerController error: ${e.message}")
            }

            Log.d(TAG, "Setting TunFd: $tunFd")
            LibXray.setTunFd(tunFd)

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

            val runningState = try { LibXray.getXrayState() } catch (_: Throwable) { false }
            val isSuccess = runningState || (error.isNullOrEmpty() && (decodedResult.contains("\"success\":true") || decodedResult == "{}" || decodedResult.isEmpty()))

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

    private fun buildManualConfig(server: VlessServer): String {
        val root = JsonObject()

        // log
        val settingsRepo = SettingsRepository(vpnService)
        val coreLogLevel = when (settingsRepo.logLevel) {
            SettingsRepository.LOG_LEVEL_NONE -> "none"
            SettingsRepository.LOG_LEVEL_ERROR -> "error"
            SettingsRepository.LOG_LEVEL_WARNING -> "warning"
            SettingsRepository.LOG_LEVEL_INFO -> "info"
            SettingsRepository.LOG_LEVEL_DEBUG -> "debug"
            else -> "warning" // auto
        }
        val log = JsonObject().apply {
            addProperty("loglevel", coreLogLevel)
        }
        root.add("log", log)

        // dns
        val dns = JsonObject().apply {
            val servers = JsonArray().apply {
                add("77.88.8.8")
                add("1.1.1.1")
                add("8.8.8.8")
            }
            add("servers", servers)
            addProperty("queryStrategy", "UseIPv4")
        }
        root.add("dns", dns)

        // policy: 4MB buffer per connection for maximum throughput on Speedtest and 4K video
        val policy = JsonObject().apply {
            val levels = JsonObject().apply {
                val level0 = JsonObject().apply {
                    addProperty("handshake", 4)
                    addProperty("connIdle", 300)
                    addProperty("uplinkOnly", 2)
                    addProperty("downlinkOnly", 5)
                    addProperty("bufferSize", 4096)
                }
                add("0", level0)
            }
            add("levels", levels)
        }
        root.add("policy", policy)

        // inbounds: tun + socks
        val inbounds = JsonArray()

        val tunInbound = JsonObject().apply {
            addProperty("tag", "tun")
            addProperty("protocol", "tun")
            val settings = JsonObject().apply {
                addProperty("name", "tun0")
                addProperty("mtu", 1420)
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

        // outbounds: vless proxy + freedom direct
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
                }
                users.add(user)
                add("users", users)
            }
            vnext.add(node)
            settings.add("vnext", vnext)
            add("settings", settings)

            val streamSettings = JsonObject().apply {
                addProperty("network", server.network)
                addProperty("security", "reality")

                val realitySettings = JsonObject().apply {
                    addProperty("publicKey", server.publicKey)
                    addProperty("serverName", server.serverName)
                    addProperty("fingerprint", server.fingerprint.ifEmpty { "edge" })
                    addProperty("shortId", server.shortId)
                }
                add("realitySettings", realitySettings)

                if (server.network == "xhttp") {
                    val xhttpSettings = JsonObject().apply {
                        addProperty("host", server.host.ifEmpty { server.serverName })
                        addProperty("mode", server.mode.ifEmpty { "packet-up" })
                        addProperty("path", server.path.ifEmpty { "/widgetComponent.js" })
                    }
                    add("xhttpSettings", xhttpSettings)
                }
            }
            add("streamSettings", streamSettings)
        }
        outbounds.add(vlessOutbound)

        val directOutbound = JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
            val settings = JsonObject().apply {
                addProperty("domainStrategy", "UseIPv4")
            }
            add("settings", settings)
        }
        outbounds.add(directOutbound)

        // Block outbound for dropping problematic UDP 443 (QUIC)
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
        val isDirectRu = settingsRepo.isDirectRuEnabled
        val geoDir = File(vpnService.filesDir, "geo")
        val geositeFile = File(geoDir, "geosite.dat")
        val geoipFile = File(geoDir, "geoip.dat")
        val hasGeoSite = geositeFile.exists() && geositeFile.length() > 100_000 && GeoRoutingRepository.hasCategoryRu(geositeFile)
        val hasGeoIp = geoipFile.exists() && geoipFile.length() > 500_000

        val routing = JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            val rules = JsonArray()

            // 0. Intercept DNS (port 53 UDP) into Xray internal DNS resolver
            val dnsRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("inboundTag", "tun")
                addProperty("port", "53")
                addProperty("network", "udp")
                addProperty("outboundTag", "dns-out")
            }
            rules.add(dnsRule)

            // Custom Bypassed Websites (User-defined exceptions routed directly without VPN)
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

            if (isDirectRu) {
                // 1. Direct Russian Domains (TOP PRIORITY: bypasses proxy immediately for Russian websites)
                val directDomainRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")

                    val domains = JsonArray().apply {
                        if (hasGeoSite) {
                            add("geosite:category-ru")
                            add("geosite:tld-ru")
                        }
                        // Built-in standard Russian services and CDNs (works 100% reliably even without geo files)
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

                // 2. Direct Russian & Private IP addresses (SECOND PRIORITY: direct connection for RU subnets & local LAN)
                val directIpRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")

                    val ips = JsonArray().apply {
                        // Russian DNS servers (direct routing for zero latency)
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

                        // Core Russian subnets (Yandex, VK) to ensure immediate direct routing even before SNI sniffing completes
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

            // 3. Block QUIC (UDP 443): prevents YouTube packet stalls and drops on foreign connections
            val blockQuicRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("port", "443")
                addProperty("network", "udp")
                addProperty("outboundTag", "block")
            }
            rules.add(blockQuicRule)

            // 4. Route all remaining regular TCP/UDP traffic to proxy (LAST RULE: global Internet & blocked sites)
            val allRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "tcp,udp")
                addProperty("outboundTag", "proxy")
            }
            rules.add(allRule)

            add("rules", rules)
        }
        root.add("routing", routing)

        return root.toString()
    }

    companion object {
        private const val TAG = "XrayVpnController"
    }
}
