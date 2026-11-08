package com.naua_security_mirage.app

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.naua_security_mirage.app.core.VpnCoreService
import com.naua_security_mirage.app.data.config.AppConfigRepository
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.*
import com.naua_security_mirage.app.data.supabase.SupabaseConfig
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.screens.*
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors
import com.naua_security_mirage.app.ui.theme.MirageTheme
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import javax.swing.SwingUtilities
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

enum class Screen {
    MAIN,
    SETTINGS,
    APPEARANCE,
    APPS,
    WEBSITES,
    LOGS,
    DNS,
    PRIVATE_DNS,
}

fun main() {
    try {
        java.net.Socket("localhost", 45678).use { it.getOutputStream().write(1) }
        kotlin.system.exitProcess(0)
    } catch (e: Exception) { }

    application {
        val instanceLock = remember {
            try {
                val f = File(System.getProperty("user.home"), ".naua-mirage.lock")
                val ch = FileChannel.open(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                ch.tryLock()
            } catch (e: Exception) { null }
        }
        if (instanceLock == null) {
            LaunchedEffect(Unit) { exitApplication() }
            return@application
        }

        val settingsRepo = remember { SettingsRepository() }
        val deviceIdRepo = remember { DeviceIdRepository() }
        val keyRepo = remember { VlessKeyRepository(deviceIdRepo) }
        val pingRepo = remember { PingRepository() }
        val geoRepo = remember { GeoRoutingRepository(settingsRepo) }
        val vpnCore = remember { VpnCoreService(settingsRepo, geoRepo) }

        val scope = rememberCoroutineScope()

        var currentScreen by remember { mutableStateOf(Screen.MAIN) }
        var selectedPlan by remember { mutableStateOf(settingsRepo.selectedServerPlan) }
        var servers by remember { mutableStateOf<List<VlessServer>>(emptyList()) }
        var bestServer by remember { mutableStateOf<VlessServer?>(null) }
        var appearanceCounter by remember { mutableStateOf(0) }
        var isVisible by remember { mutableStateOf(true) }

        val serverSocket = remember { try { ServerSocket(45678) } catch (e: Exception) { null } }
        LaunchedEffect(serverSocket) {
            serverSocket?.let { ss ->
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    while (isActive) {
                        try {
                            val client = ss.accept()
                            client.getInputStream().read()
                            SwingUtilities.invokeLater { isVisible = true }
                            client.close()
                        } catch (e: Exception) { }
                    }
                }
            }
        }

        var isRefreshing by remember { mutableStateOf(false) }
        var sessionSeconds by remember { mutableStateOf(0L) }

        val vpnState by vpnCore.vpnState.collectAsState()
        val downloadSpeed by vpnCore.downloadSpeed.collectAsState()
        val uploadSpeed by vpnCore.uploadSpeed.collectAsState()

        var previousVpnState by remember { mutableStateOf(vpnState) }
        LaunchedEffect(vpnState) {
            if (previousVpnState == VpnState.CONNECTED && vpnState != VpnState.CONNECTED) {
                if (sessionSeconds > 1) {
                    val tag = if (settingsRepo.isPaidPlan(selectedPlan)) {
                        AppConfigRepository.node(selectedPlan)?.labelRu ?: "Франция (Платная)"
                    } else {
                        bestServer?.tag ?: "Бесплатный"
                    }
                    com.naua_security_mirage.app.data.SessionJournal.add(tag, sessionSeconds)
                }
            }
            previousVpnState = vpnState
            if (vpnState == VpnState.CONNECTED) {
                sessionSeconds = 0L
                while (isActive) {
                    delay(1000)
                    sessionSeconds++
                }
            } else {
                sessionSeconds = 0L
            }
        }

        LaunchedEffect(Unit) {
            AppLogger.init(settingsRepo)
            geoRepo.initFromBundledIfNeeded()
            AppConfigRepository.init(settingsRepo)

            scope.launch {
                try {
                    val fetched = keyRepo.getVlessServers()
                    servers = fetched
                    val measured = pingRepo.measureAllPings(fetched)
                    servers = measured
                    bestServer = pingRepo.selectBestServer(measured)
                } catch (e: Exception) {
                    AppLogger.e("Main", "Ошибка загрузки серверов: ${e.message}")
                }
            }
        }

        val windowState = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            size = androidx.compose.ui.unit.DpSize(410.dp, 690.dp)
        )

        val appIcon = painterResource("icon.png")

        Tray(
            icon = appIcon,
            tooltip = "NAUA Security Mirage PC",
            onAction = { isVisible = true },
            menu = {
                Item("Открыть", onClick = { isVisible = true })
                Separator()
                Item(
                    if (vpnState == VpnState.CONNECTED) "Отключить VPN" else "Подключить VPN",
                    onClick = {
                        scope.launch {
                            if (vpnState == VpnState.CONNECTED) {
                                vpnCore.stop()
                            } else {
                                if (settingsRepo.isPaidPlan(selectedPlan)) {
                                    val paidNode = AppConfigRepository.node(selectedPlan)
                                        ?: AppConfigRepository.node(settingsRepo.lastPaidPlan)
                                    val key = if (paidNode != null) SupabaseManager.instance.ensurePaidNodeKey(paidNode) else null
                                    val targetServer = if (!key.isNullOrBlank()) {
                                        VlessServer.fromUri(key, paidNode?.labelRu)
                                    } else {
                                        val user = SupabaseManager.instance.currentUser.value
                                        VlessServer(
                                            id = "france-premium",
                                            tag = paidNode?.labelRu ?: "Франция (Платная)",
                                            address = SupabaseConfig.FRANCE_HOST,
                                            port = SupabaseConfig.FRANCE_PORT,
                                            uuid = user?.id ?: SupabaseConfig.FRANCE_DEFAULT_UUID,
                                            network = "tcp",
                                            security = "reality",
                                            publicKey = SupabaseConfig.FRANCE_PBK,
                                            fingerprint = SupabaseConfig.FRANCE_FINGERPRINT,
                                            serverName = SupabaseConfig.FRANCE_SNI,
                                            host = SupabaseConfig.FRANCE_SNI,
                                            mode = "none",
                                            path = SupabaseConfig.FRANCE_SPX,
                                            shortId = SupabaseConfig.FRANCE_SID,
                                            flow = "xtls-rprx-vision"
                                        )
                                    }
                                    if (targetServer != null) {
                                        bestServer = targetServer
                                        vpnCore.start(targetServer)
                                    }
                                } else {
                                    val s = bestServer ?: servers.firstOrNull()
                                    if (s != null) vpnCore.start(s)
                                }
                            }
                        }
                    }
                )
                Separator()
                Item("Выход", onClick = {
                    scope.launch {
                        vpnCore.stop()
                        exitApplication()
                    }
                })
            }
        )

        Window(
            onCloseRequest = {
                if (settingsRepo.isStatusNotificationEnabled) {
                    isVisible = false
                } else {
                    scope.launch {
                        vpnCore.stop()
                        exitApplication()
                    }
                }
            },
            state = windowState,
            visible = isVisible,
            title = "Mirage - NAUA Security",
            icon = appIcon,
            undecorated = false,
            resizable = true
        ) {
            window.minimumSize = Dimension(380, 640)

            MirageTheme(settingsRepository = settingsRepo) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LocalMirageColors.current.bg)
                ) {
                    Crossfade(targetState = currentScreen) { screen ->
                        when (screen) {
                            Screen.MAIN -> MainScreen(
                                vpnState = vpnState,
                                currentServer = bestServer,
                                downloadSpeed = downloadSpeed,
                                uploadSpeed = uploadSpeed,
                                settingsRepository = settingsRepo,
                                selectedPlan = selectedPlan,
                                onSelectPlan = { plan ->
                                    val before = selectedPlan
                                    selectedPlan = plan
                                    settingsRepo.selectedServerPlan = plan
                                    if (settingsRepo.isPaidPlan(plan)) {
                                        settingsRepo.lastPaidPlan = plan
                                    }
                                    if (before != plan && (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING)) {
                                        scope.launch {
                                            vpnCore.stop()
                                            delay(400)
                                            if (settingsRepo.isPaidPlan(plan)) {
                                                val paidNode = AppConfigRepository.node(plan)
                                                val key = if (paidNode != null) SupabaseManager.instance.ensurePaidNodeKey(paidNode) else null
                                                val target = if (!key.isNullOrBlank()) {
                                                    VlessServer.fromUri(key, paidNode?.labelRu)
                                                } else {
                                                    val user = SupabaseManager.instance.currentUser.value
                                                    VlessServer(
                                                        id = "france-premium",
                                                        tag = paidNode?.labelRu ?: "Франция (Платная)",
                                                        address = SupabaseConfig.FRANCE_HOST,
                                                        port = SupabaseConfig.FRANCE_PORT,
                                                        uuid = user?.id ?: SupabaseConfig.FRANCE_DEFAULT_UUID,
                                                        network = "tcp",
                                                        security = "reality",
                                                        publicKey = SupabaseConfig.FRANCE_PBK,
                                                        fingerprint = SupabaseConfig.FRANCE_FINGERPRINT,
                                                        serverName = SupabaseConfig.FRANCE_SNI,
                                                        host = SupabaseConfig.FRANCE_SNI,
                                                        mode = "none",
                                                        path = SupabaseConfig.FRANCE_SPX,
                                                        shortId = SupabaseConfig.FRANCE_SID,
                                                        flow = "xtls-rprx-vision"
                                                    )
                                                }
                                                if (target != null) {
                                                    bestServer = target
                                                    vpnCore.start(target)
                                                }
                                            } else {
                                                val s = bestServer ?: servers.firstOrNull()
                                                if (s != null) vpnCore.start(s)
                                            }
                                        }
                                    }
                                },
                                sessionSeconds = sessionSeconds,
                                isRefreshing = isRefreshing,
                                onToggleConnect = {
                                    scope.launch {
                                        if (vpnState == VpnState.CONNECTED) {
                                            vpnCore.stop()
                                        } else {
                                            if (settingsRepo.isPaidPlan(selectedPlan)) {
                                                val paidNode = AppConfigRepository.node(selectedPlan)
                                                    ?: AppConfigRepository.node(settingsRepo.lastPaidPlan)
                                                val key = if (paidNode != null) SupabaseManager.instance.ensurePaidNodeKey(paidNode) else null
                                                val targetServer = if (!key.isNullOrBlank()) {
                                                    VlessServer.fromUri(key, paidNode?.labelRu)
                                                } else {
                                                    val user = SupabaseManager.instance.currentUser.value
                                                    VlessServer(
                                                        id = "france-premium",
                                                        tag = paidNode?.labelRu ?: "Франция (Платная)",
                                                        address = SupabaseConfig.FRANCE_HOST,
                                                        port = SupabaseConfig.FRANCE_PORT,
                                                        uuid = user?.id ?: SupabaseConfig.FRANCE_DEFAULT_UUID,
                                                        network = "tcp",
                                                        security = "reality",
                                                        publicKey = SupabaseConfig.FRANCE_PBK,
                                                        fingerprint = SupabaseConfig.FRANCE_FINGERPRINT,
                                                        serverName = SupabaseConfig.FRANCE_SNI,
                                                        host = SupabaseConfig.FRANCE_SNI,
                                                        mode = "none",
                                                        path = SupabaseConfig.FRANCE_SPX,
                                                        shortId = SupabaseConfig.FRANCE_SID,
                                                        flow = "xtls-rprx-vision"
                                                    )
                                                }
                                                if (targetServer != null) {
                                                    bestServer = targetServer
                                                    vpnCore.start(targetServer)
                                                }
                                            } else {
                                                val s = bestServer ?: servers.firstOrNull()
                                                if (s != null) {
                                                    vpnCore.start(s)
                                                } else {
                                                    val fetched = keyRepo.getVlessServers()
                                                    servers = fetched
                                                    val best = pingRepo.selectBestServer(fetched)
                                                    bestServer = best
                                                    if (best != null) vpnCore.start(best)
                                                }
                                            }
                                        }
                                    }
                                },
                                onRefreshServers = {
                                    scope.launch {
                                        isRefreshing = true
                                        try {
                                            AppConfigRepository.refresh(force = true)
                                            val fetched = keyRepo.getVlessServers()
                                            val measured = pingRepo.measureAllPings(fetched)
                                            servers = measured
                                            bestServer = pingRepo.selectBestServer(measured)
                                        } finally {
                                            isRefreshing = false
                                        }
                                    }
                                },
                                onOpenSettings = { currentScreen = Screen.SETTINGS }
                            )

                            Screen.SETTINGS -> SettingsScreen(
                                settingsRepository = settingsRepo,
                                onBack = { currentScreen = Screen.MAIN },
                                onNavigateToAppearance = { currentScreen = Screen.APPEARANCE },
                                onNavigateToApps = { currentScreen = Screen.APPS },
                                onNavigateToWebsites = { currentScreen = Screen.WEBSITES },
                                onNavigateToLogs = { currentScreen = Screen.LOGS },
                                onNavigateToDns = { currentScreen = Screen.DNS },
                                onNavigateToPrivateDns = { currentScreen = Screen.PRIVATE_DNS }
                            )

                            Screen.APPEARANCE -> AppearanceScreen(
                                settingsRepository = settingsRepo,
                                onBack = { currentScreen = Screen.SETTINGS },
                                onAppearanceChanged = { appearanceCounter++ }
                            )

                            Screen.APPS -> AppsScreen(
                                settingsRepository = settingsRepo,
                                onBack = { currentScreen = Screen.SETTINGS }
                            )

                            Screen.WEBSITES -> WebsitesScreen(
                                settingsRepository = settingsRepo,
                                onBack = { currentScreen = Screen.SETTINGS }
                            )

                            Screen.LOGS -> LogsScreen(
                                settingsRepository = settingsRepo,
                                onBack = { currentScreen = Screen.SETTINGS }
                            )

                            Screen.DNS -> DnsScreen(
                                settingsRepository = settingsRepo,
                                onBack = { currentScreen = Screen.SETTINGS }
                            )

                            Screen.PRIVATE_DNS -> PrivateDnsScreen(
                                onBack = { currentScreen = Screen.SETTINGS }
                            )
                        }
                    }
                }
            }
        }
    }
}
