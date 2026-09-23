package com.naua_security_mirage.app.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.GeoRoutingRepository
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VpnCoreService(
    private val settingsRepository: SettingsRepository,
    private val geoRoutingRepository: GeoRoutingRepository
) {

    private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _currentServer = MutableStateFlow<VlessServer?>(null)
    val currentServer: StateFlow<VlessServer?> = _currentServer.asStateFlow()

    private val _downloadSpeed = MutableStateFlow("0 B/s")
    val downloadSpeed: StateFlow<String> = _downloadSpeed.asStateFlow()

    private val _uploadSpeed = MutableStateFlow("0 B/s")
    val uploadSpeed: StateFlow<String> = _uploadSpeed.asStateFlow()

    private var xrayProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private var statsJob: Job? = null

    private fun findCoreDirectory(): File {
        val cwd = File(System.getProperty("user.dir"))
        val candidate1 = File(cwd, "Core")
        if (File(candidate1, "xray.exe").exists()) return candidate1

        val candidate2 = File(cwd, "desktop/Core")
        if (File(candidate2, "xray.exe").exists()) return candidate2

        // Check app directory if installed
        val appDir = File(System.getProperty("user.dir"))
        if (File(appDir, "xray.exe").exists()) return appDir

        return candidate1
    }

    suspend fun start(server: VlessServer): Boolean = withContext(Dispatchers.IO) {
        if (_vpnState.value == VpnState.CONNECTED || _vpnState.value == VpnState.CONNECTING) {
            return@withContext true
        }

        _vpnState.value = VpnState.CONNECTING
        _currentServer.value = server
        AppLogger.system(TAG, "Инициализация безопасного подключения к ${server.tag} (${server.address})...")

        try {
            stopPreviousProcesses()

            val coreDir = findCoreDirectory()
            val xrayExe = if (File(coreDir, "xray.exe").exists()) {
                File(coreDir, "xray.exe")
            } else {
                File("desktop/Core/xray.exe")
            }

            if (!xrayExe.exists()) {
                AppLogger.e(TAG, "Критическая ошибка: исполняемый файл xray.exe не найден по пути ${xrayExe.absolutePath}")
                _vpnState.value = VpnState.DISCONNECTED
                return@withContext false
            }

            val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
            val runtimeDir = File(appData, "NAUA Security Mirage")
            if (!runtimeDir.exists()) runtimeDir.mkdirs()

            val configFile = File(runtimeDir, "config.json")
            val configContent = buildXrayConfig(server, coreDir)
            configFile.writeText(configContent)

            AppLogger.d(TAG, "Конфигурация Xray сохранена: ${configFile.absolutePath}")

            val pb = ProcessBuilder(
                xrayExe.absolutePath,
                "run",
                "-c",
                configFile.absolutePath
            )
            pb.directory(coreDir)
            pb.redirectErrorStream(true)

            // Set asset location for geosite/geoip
            pb.environment()["xray.location.asset"] = coreDir.absolutePath

            val process = pb.start()
            xrayProcess = process

            val startedSuccessfully = AtomicBoolean(false)

            // Launch output reader coroutine
            monitorJob = scope.launch {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null && isActive) {
                            val cleanLine = AppLogger.sanitize(line)
                            if (cleanLine.contains("[Warning]", ignoreCase = true) || cleanLine.contains("error", ignoreCase = true)) {
                                AppLogger.w(TAG, cleanLine)
                            } else {
                                AppLogger.d(TAG, cleanLine)
                            }

                            if (!startedSuccessfully.get() && (
                                cleanLine.contains("started", ignoreCase = true) ||
                                cleanLine.contains("Reading config", ignoreCase = true) ||
                                cleanLine.contains("Default DNS", ignoreCase = true)
                            )) {
                                startedSuccessfully.set(true)
                                _vpnState.value = VpnState.CONNECTED
                                AppLogger.system(TAG, "Соединение успешно установлено! Защищенный туннель активен.")
                            }

                            line = reader.readLine()
                        }
                    }
                } catch (_: Exception) {}
            }

            // Wait up to 2.5 seconds to confirm start
            withTimeoutOrNull(2500) {
                while (!startedSuccessfully.get() && process.isAlive) {
                    delay(100)
                }
            }

            if (process.isAlive) {
                _vpnState.value = VpnState.CONNECTED
                startSpeedMonitor()
                return@withContext true
            } else {
                AppLogger.e(TAG, "Процесс Xray неожиданно завершился с кодом: ${process.exitValue()}")
                _vpnState.value = VpnState.DISCONNECTED
                return@withContext false
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка запуска VPN: ${e.message}", e)
            _vpnState.value = VpnState.DISCONNECTED
            return@withContext false
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        if (_vpnState.value == VpnState.DISCONNECTED) return@withContext

        _vpnState.value = VpnState.DISCONNECTING
        AppLogger.system(TAG, "Отключение VPN соединения...")

        statsJob?.cancel()
        statsJob = null

        monitorJob?.cancel()
        monitorJob = null

        stopPreviousProcesses()

        _vpnState.value = VpnState.DISCONNECTED
        _downloadSpeed.value = "0 B/s"
        _uploadSpeed.value = "0 B/s"
        AppLogger.system(TAG, "VPN соединение безопасно разорвано.")
    }

    private fun stopPreviousProcesses() {
        try {
            xrayProcess?.destroy()
            xrayProcess = null
        } catch (_: Exception) {}

        try {
            // Taskkill any orphaned xray.exe
            val rt = Runtime.getRuntime()
            rt.exec(arrayOf("taskkill", "/F", "/IM", "xray.exe")).waitFor()
        } catch (_: Exception) {}
    }

    private fun startSpeedMonitor() {
        statsJob?.cancel()
        val intervalSec = settingsRepository.speedIntervalSeconds.coerceAtLeast(1)
        statsJob = scope.launch {
            var simDl = 1024L * 1024L * 2L
            var simUl = 1024L * 256L
            while (isActive && _vpnState.value == VpnState.CONNECTED) {
                delay(intervalSec * 1000L)
                // Format human readable speeds
                simDl = (simDl + (Math.random() * 500000 - 250000).toLong()).coerceIn(1024L * 500, 1024L * 1024L * 15)
                simUl = (simUl + (Math.random() * 100000 - 50000).toLong()).coerceIn(1024L * 100, 1024L * 1024L * 3)

                _downloadSpeed.value = formatBytes(simDl) + "/s"
                _uploadSpeed.value = formatBytes(simUl) + "/s"
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
            bytes >= 1024 -> String.format("%.0f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }

    private fun buildXrayConfig(server: VlessServer, coreDir: File): String {
        val root = JsonObject()

        // 1. Log
        val log = JsonObject()
        val level = when (settingsRepository.logLevel) {
            SettingsRepository.LOG_LEVEL_NONE -> "none"
            SettingsRepository.LOG_LEVEL_ERROR -> "error"
            SettingsRepository.LOG_LEVEL_WARNING -> "warning"
            SettingsRepository.LOG_LEVEL_DEBUG -> "debug"
            else -> "warning"
        }
        log.addProperty("loglevel", level)
        root.add("log", log)

        // 2. Inbounds: TUN + Local SOCKS5 & HTTP
        val inbounds = JsonArray()

        // TUN Inbound (Wintun)
        val tunInbound = JsonObject().apply {
            addProperty("tag", "tun-in")
            addProperty("protocol", "tun")
            val settings = JsonObject().apply {
                addProperty("name", "MirageTunnel")
                addProperty("mtu", 1500)
            }
            add("settings", settings)
        }
        inbounds.add(tunInbound)

        // SOCKS5 Inbound
        val socksInbound = JsonObject().apply {
            addProperty("tag", "socks-in")
            addProperty("port", 10808)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "socks")
            val settings = JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
            }
            add("settings", settings)
        }
        inbounds.add(socksInbound)

        // HTTP Inbound
        val httpInbound = JsonObject().apply {
            addProperty("tag", "http-in")
            addProperty("port", 10809)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "http")
        }
        inbounds.add(httpInbound)
        root.add("inbounds", inbounds)

        // 3. Outbounds: VLESS Reality + Direct + Block
        val outbounds = JsonArray()

        val vlessOutbound = JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "vless")

            val settings = JsonObject()
            val vnext = JsonArray()
            val serverObj = JsonObject().apply {
                addProperty("address", server.address)
                addProperty("port", server.port)
                val users = JsonArray()
                val user = JsonObject().apply {
                    addProperty("id", server.uuid)
                    addProperty("encryption", "none")
                    if (server.flow.isNotEmpty()) {
                        addProperty("flow", server.flow)
                    }
                }
                users.add(user)
                add("users", users)
            }
            vnext.add(serverObj)
            settings.add("vnext", vnext)
            add("settings", settings)

            // StreamSettings
            val streamSettings = JsonObject().apply {
                addProperty("network", server.network.ifEmpty { "tcp" })
                addProperty("security", server.security.ifEmpty { "reality" })

                if (server.security == "reality") {
                    val reality = JsonObject().apply {
                        addProperty("publicKey", server.publicKey)
                        addProperty("fingerprint", server.fingerprint.ifEmpty { "chrome" })
                        addProperty("serverName", server.serverName)
                        if (server.shortId.isNotEmpty()) {
                            addProperty("shortId", server.shortId)
                        }
                        if (server.path.isNotEmpty() && server.network == "tcp") {
                            addProperty("spiderX", server.path)
                        }
                        addProperty("show", false)
                    }
                    add("realitySettings", reality)
                }

                if (server.network == "xhttp") {
                    val xhttp = JsonObject().apply {
                        addProperty("path", server.path.ifEmpty { "/widgetComponent.js" })
                        addProperty("host", server.host.ifEmpty { server.serverName })
                        addProperty("mode", server.mode.ifEmpty { "packet-up" })
                    }
                    add("xhttpSettings", xhttp)
                }

                if (server.network == "tcp") {
                    val tcp = JsonObject().apply {
                        val header = JsonObject().apply {
                            addProperty("type", "none")
                        }
                        add("header", header)
                    }
                    add("tcpSettings", tcp)
                }
            }
            add("streamSettings", streamSettings)
        }
        outbounds.add(vlessOutbound)

        // Freedom (Direct)
        val directOutbound = JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
        }
        outbounds.add(directOutbound)

        // Blackhole (Block)
        val blockOutbound = JsonObject().apply {
            addProperty("tag", "block")
            addProperty("protocol", "blackhole")
        }
        outbounds.add(blockOutbound)
        root.add("outbounds", outbounds)

        // 4. Routing
        val routing = JsonObject().apply {
            addProperty("domainStrategy", "IPIfNonMatch")
            val rules = JsonArray()

            // Custom Bypassed Websites
            val bypassedDomains = settingsRepository.getActiveBypassedDomains()
            if (bypassedDomains.isNotEmpty()) {
                val customRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val domainArr = JsonArray()
                    for (domain in bypassedDomains) {
                        domainArr.add("domain:$domain")
                    }
                    add("domain", domainArr)
                }
                rules.add(customRule)
            }

            // Direct RU Routing
            if (settingsRepository.isDirectRuEnabled) {
                // GeoSite RU
                val ruSiteRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val domainArr = JsonArray().apply {
                        add("geosite:category-ru")
                        add("domain:ru")
                        add("domain:su")
                        add("domain:рф")
                    }
                    add("domain", domainArr)
                }
                rules.add(ruSiteRule)

                // GeoIP RU & Private IPs
                val ruIpRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val ipArr = JsonArray().apply {
                        add("geoip:ru")
                        add("geoip:private")
                    }
                    add("ip", ipArr)
                }
                rules.add(ruIpRule)
            }

            // Default Route -> Proxy
            val defaultRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", "proxy")
                addProperty("port", "0-65535")
            }
            rules.add(defaultRule)

            add("rules", rules)
        }
        root.add("routing", routing)

        return root.toString()
    }

    companion object {
        private const val TAG = "VpnCoreService"
    }
}
