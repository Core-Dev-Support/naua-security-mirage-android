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
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.*
import com.naua_security_mirage.app.data.supabase.SupabaseConfig
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.screens.*
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageTheme
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.io.File

enum class Screen {
    MAIN,
    SETTINGS,
    APPEARANCE,
    APPS,
    WEBSITES,
    LOGS
}

fun main() = application {
    val settingsRepo = remember { SettingsRepository() }
    val deviceIdRepo = remember { DeviceIdRepository() }
    val keyRepo = remember { VlessKeyRepository(deviceIdRepo) }
    val pingRepo = remember { PingRepository() }
    val geoRepo = remember { GeoRoutingRepository(settingsRepo) }
    val vpnCore = remember { VpnCoreService(settingsRepo, geoRepo) }

    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(Screen.MAIN) }
    var selectedPlan by remember { mutableStateOf(ServerPlan.FREE) }
    var servers by remember { mutableStateOf<List<VlessServer>>(emptyList()) }
    var bestServer by remember { mutableStateOf<VlessServer?>(null) }
    var appearanceCounter by remember { mutableStateOf(0) }
    var isVisible by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var sessionSeconds by remember { mutableStateOf(0L) }

    val vpnState by vpnCore.vpnState.collectAsState()
    val downloadSpeed by vpnCore.downloadSpeed.collectAsState()
    val uploadSpeed by vpnCore.uploadSpeed.collectAsState()

    // Session timer loop when CONNECTED
    LaunchedEffect(vpnState) {
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

    // Initialize services
    LaunchedEffect(Unit) {
        AppLogger.init(settingsRepo)
        geoRepo.initFromBundledIfNeeded()

        // Fetch servers & measure ping
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
        size = androidx.compose.ui.unit.DpSize(400.dp, 680.dp)
    )

    val appIcon = painterResource("icon.png")

    // System Tray
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
                                val s = bestServer ?: servers.firstOrNull()
                                if (s != null) vpnCore.start(s)
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
                isVisible = false // Minimize to tray
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
            val colors = LocalMirageColors.current

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bg)
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
                            onSelectPlan = { selectedPlan = it },
                            sessionSeconds = sessionSeconds,
                            isRefreshing = isRefreshing,
                            onToggleConnect = {
                                scope.launch {
                                    if (vpnState == VpnState.CONNECTED) {
                                        vpnCore.stop()
                                    } else {
                                        if (selectedPlan == ServerPlan.PREMIUM_FRANCE) {
                                            val user = SupabaseManager.instance.currentUser.value
                                            SupabaseManager.instance.ensureFranceVlessKey()
                                            val franceServer = VlessServer(
                                                id = "france-premium",
                                                tag = "Франция (Платный)",
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
                                            bestServer = franceServer
                                            vpnCore.start(franceServer)
                                        } else {
                                            val s = bestServer ?: servers.firstOrNull()
                                            if (s != null) {
                                                vpnCore.start(s)
                                            } else {
                                                val fetched = keyRepo.getVlessServers()
                                                servers = fetched
                                                val best = pingRepo.selectBestServer(fetched)
                                                bestServer = best
                                                vpnCore.start(best)
                                            }
                                        }
                                    }
                                }
                            },
                            onRefreshServers = {
                                scope.launch {
                                    isRefreshing = true
                                    try {
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
                            onNavigateToLogs = { currentScreen = Screen.LOGS }
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
                    }
                }
            }
        }
    }
}
