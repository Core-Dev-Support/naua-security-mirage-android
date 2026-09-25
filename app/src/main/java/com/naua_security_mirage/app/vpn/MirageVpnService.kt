package com.naua_security_mirage.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.VpnService
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.DeviceIdRepository
import com.naua_security_mirage.app.data.repository.PingRepository
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.data.repository.VlessKeyRepository
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SpeedInfo(
    val downBps: Long = 0L,
    val upBps: Long = 0L,
    val isEnabled: Boolean = true
)

class MirageVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var connectionJob: Job? = null
    private var pingJob: Job? = null
    private var timerJob: Job? = null
    private var speedJob: Job? = null

    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var notificationManager: VpnNotificationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var vlessKeyRepository: VlessKeyRepository
    private lateinit var pingRepository: PingRepository
    private var xrayController: XrayVpnController? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastKnownNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        com.naua_security_mirage.app.util.AppShield.checkIntegrity(this)
        notificationManager = VpnNotificationManager(this)
        settingsRepository = SettingsRepository(this)
        vlessKeyRepository = VlessKeyRepository(this, DeviceIdRepository(this))
        pingRepository = PingRepository()
        xrayController = XrayVpnController(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        serviceScope.launch {
            _vpnState.collect {
                MirageTileService.requestUpdate(this@MirageVpnService)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_DISCONNECT -> {
                stopVpn()
            }
            ACTION_RECONNECT -> {
                reconnectVpn()
            }
            ACTION_CONNECT -> {
                if (_vpnState.value == VpnState.DISCONNECTED) {
                    if (prepare(this) != null) {
                        // Satisfy getForegroundService contract before launching activity
                        val initialNotification = notificationManager.buildDisconnectedNotification()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            startForeground(
                                VpnNotificationManager.NOTIFICATION_ID,
                                initialNotification,
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                            )
                        } else {
                            startForeground(VpnNotificationManager.NOTIFICATION_ID, initialNotification)
                        }
                        
                        val quickIntent = Intent(this, com.naua_security_mirage.app.ui.MainActivity::class.java).apply {
                            this.action = com.naua_security_mirage.app.ui.MainActivity.ACTION_QUICK_CONNECT
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(quickIntent)
                        
                        // Stop this service as we are passing control to MainActivity
                        stopForegroundCompat(true)
                        stopSelf()
                    } else {
                        startVpn()
                    }
                } else {
                    startVpn()
                }
            }
            else -> {
                if (_vpnState.value == VpnState.DISCONNECTED) {
                    if (prepare(this) != null) {
                        // Satisfy getForegroundService contract before launching activity
                        val initialNotification = notificationManager.buildDisconnectedNotification()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            startForeground(
                                VpnNotificationManager.NOTIFICATION_ID,
                                initialNotification,
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                            )
                        } else {
                            startForeground(VpnNotificationManager.NOTIFICATION_ID, initialNotification)
                        }
                        
                        val quickIntent = Intent(this, com.naua_security_mirage.app.ui.MainActivity::class.java).apply {
                            this.action = com.naua_security_mirage.app.ui.MainActivity.ACTION_QUICK_CONNECT
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(quickIntent)
                        
                        // Stop this service as we are passing control to MainActivity
                        stopForegroundCompat(true)
                        stopSelf()
                    } else {
                        startVpn()
                    }
                } else {
                    startVpn()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startVpn() {
        val initialNotification = notificationManager.buildConnectedNotification(
            0L,
            "00:00",
            isSpeedEnabled = (settingsRepository.speedIntervalSeconds > 0)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                VpnNotificationManager.NOTIFICATION_ID,
                initialNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(VpnNotificationManager.NOTIFICATION_ID, initialNotification)
        }

        if (_vpnState.value == VpnState.CONNECTED || _vpnState.value == VpnState.CONNECTING) {
            return
        }

        _vpnState.value = VpnState.CONNECTING
        AppLogger.i(TAG, "Initiating VPN connection...")

        val previousConnectionJob = connectionJob
        connectionJob = serviceScope.launch {
            // A previous attempt may still own the Xray core or the ParcelFileDescriptor.
            // Join it before starting another attempt; otherwise an old job can close the
            // newly-created TUN and make every node look dead.
            previousConnectionJob?.cancelAndJoin()
            if (_vpnState.value != VpnState.CONNECTING) return@launch
            try {
                val isFrancePlan = settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE &&
                        com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()

                val bestServer = if (isFrancePlan) {
                    com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.ensureFranceVlessKey()
                    val activeKey = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.getActiveVlessKey()
                    val parsedServer = if (!activeKey.isNullOrBlank()) {
                        com.naua_security_mirage.app.data.model.VlessServer.fromUri(activeKey, "Франция (Платный)")
                    } else null

                    val france = parsedServer ?: run {
                        val clientUuid = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.getActiveClientUuid()
                        val clientFlow = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.getActiveClientFlow(clientUuid)
                        com.naua_security_mirage.app.data.supabase.SupabaseConfig.getFranceServer(clientUuid, clientFlow)
                    }
                    AppLogger.i(TAG, "Выбран платный узел: ${france.tag} (${france.address}:${france.port}) с UUID: ${france.uuid}, flow: '${france.flow}'")
                    france
                } else {
                    // 1. Fetch 3 VLESS keys from API or fallbacks
                    val servers = vlessKeyRepository.getVlessServers()
                    AppLogger.i(TAG, "Loaded ${servers.size} servers. Measuring latency...")
                    Log.d(TAG, "Loaded ${servers.size} servers. Measuring latency...")

                    // 2. Measure ping on all 3 servers
                    val measuredServers = pingRepository.measureAllPings(servers)
                    measuredServers.forEach { s ->
                        val pingText = if (s.pingMs in 1..9998) "${s.pingMs} ms" else "таймаут"
                        AppLogger.d(TAG, "Узел ${s.tag} -> задержка: $pingText")
                    }

                    // 3. Automatically select the best server with lowest ping
                    val best = pingRepository.selectBestServer(measuredServers)
                    val bestPingText = if (best.pingMs in 1..9998) "${best.pingMs} ms" else "доступен"
                    AppLogger.i(TAG, "Выбран оптимальный узел: ${best.tag} (задержка: $bestPingText)")
                    Log.d(TAG, "Selected best server: ${best.tag} ($bestPingText)")
                    best
                }

                // 4. Establish Android VPN Tun interface (IPv4 full tunnel)
                val builder = Builder()
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)
                    .setSession("Mirage VPN")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                // Kill Switch: system blocking mode
                if (settingsRepository.isKillSwitchEnabled) {
                    builder.setBlocking(true)
                    AppLogger.i(TAG, "Kill Switch активен: TUN интерфейс переведен в блокирующий режим (setBlocking=true)")
                }


                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Throwable) {
                    Log.w(TAG, "addDisallowedApplication self failed: ${e.message}")
                }

                val bypassed = settingsRepository.bypassedAppPackages
                if (bypassed.isNotEmpty()) {
                    AppLogger.i(TAG, "Раздельное туннелирование: исключено приложений из VPN: ${bypassed.size}")
                    bypassed.forEach { pkg ->
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (e: Throwable) {
                            Log.w(TAG, "addDisallowedApplication failed for $pkg: ${e.message}")
                        }
                    }
                } else {
                    AppLogger.i(TAG, "Раздельное туннелирование: туннелируются все приложения устройства")
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    AppLogger.e(TAG, "Failed to establish VPN interface (pfd == null)")
                    Log.e(TAG, "Failed to establish VPN interface")
                    stopVpn(cancelConnectionJob = false)
                    return@launch
                }
                vpnInterface = pfd
                AppLogger.i(TAG, "VPN Tun interface established successfully (FD: ${pfd.fd})")

                // 5. Start Xray core with TUN fd and make sure real traffic flows through it.
                // A node may accept the VLESS handshake and then drop every byte, so a started
                // core alone is never treated as a working connection.
                val tunnelAttempt = withTimeoutOrNull(60_000L) {
                    openWorkingTunnel(bestServer, isFrancePlan, pfd)
                }
                if (tunnelAttempt == null) {
                    AppLogger.e(TAG, "Превышен лимит времени проверки VPN-узлов — VPN отключается.")
                    stopVpn(cancelConnectionJob = false)
                    return@launch
                }
                val (workingServer, _) = tunnelAttempt
                if (workingServer == null) {
                    AppLogger.e(TAG, "Ни один узел не подтвердил передачу трафика — VPN отключается.")
                    stopVpn(cancelConnectionJob = false)
                    return@launch
                }
                _tunnelHealthy.value = true
                val activeServer = workingServer

                // 6. Update state to CONNECTED
                _activeServer.value = activeServer
                _activePing.value = activeServer.pingMs
                _sessionSeconds.value = 0L
                _vpnState.value = VpnState.CONNECTED
                AppLogger.i(TAG, "VPN State changed to CONNECTED. Маршрутизация защищенного трафика через ${activeServer.tag} (Endpoint: Зашифрован)")

                // 7. Register network monitoring callback for handover / zero-leak reconnect
                registerNetworkMonitoring()

                // 8. Launch session timer
                startSessionTimer()

                // 9. Launch periodic ping test
                startPeriodicPing(activeServer)

                // 10. Launch real-time speed monitoring
                startSpeedMonitoring()

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Error starting VPN: ${e.message}", e)
                stopVpn(cancelConnectionJob = false)
            }
        }
    }

    /**
     * Starts Xray for the preferred node and confirms that real traffic passes through it.
     *
     * When the preferred (paid) node accepts the tunnel but forwards nothing — dead egress,
     * rejected client or lost transport parameters — the free nodes are probed automatically
     * so the user keeps a working connection instead of a silently dead one.
     */
    private suspend fun openWorkingTunnel(
        preferred: VlessServer,
        isPaidPlan: Boolean,
        pfd: ParcelFileDescriptor
    ): Pair<VlessServer?, Boolean> {
        val candidates = mutableListOf(preferred)

        try {
            val freeServers = vlessKeyRepository.getVlessServers()
            val measured = pingRepository.measureAllPings(freeServers)
            for (server in measured.sortedBy { if (it.pingMs in 1..9998) it.pingMs else Long.MAX_VALUE }) {
                if (candidates.none { it.address == server.address && it.port == server.port && it.uuid == server.uuid }) {
                    candidates += server
                }
            }
            AppLogger.i(TAG, "Подготовлено кандидатов для failover: ${candidates.size}")
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Не удалось загрузить резервные бесплатные узлы: ${e.message}")
        }

        var coreStarted = false
        for ((index, candidate) in candidates.withIndex()) {
            val hostKey = "${candidate.address}:${candidate.port}:${candidate.uuid}:${candidate.network}"
            val knownFlowLess = settingsRepository.flowStrippedHosts

            // If this node is already known to be incompatible with XTLS Vision, skip the
            // extra probe round trip and connect without the flow straight away.
            var server = if (candidate.flow.isNotBlank() && knownFlowLess.contains(hostKey)) {
                candidate.copy(flow = "")
            } else {
                candidate
            }

            var result = attemptTunnel(server, pfd)
            coreStarted = coreStarted || result.coreStarted
            if (!isCurrentTunnel(pfd)) return null to coreStarted

            if (!result.trafficFlows && server.flow.isNotBlank()) {
                AppLogger.w(TAG, "Узел ${server.tag}: XTLS Vision не пропускает трафик, повтор без flow...")
                server = server.copy(flow = "")
                result = attemptTunnel(server, pfd)
                coreStarted = coreStarted || result.coreStarted
                if (!isCurrentTunnel(pfd)) return null to coreStarted
                if (result.trafficFlows) {
                    settingsRepository.flowStrippedHosts = knownFlowLess + hostKey
                    AppLogger.i(TAG, "Узел ${server.tag} подтверждён без XTLS Vision — запомнено для этого сервера.")
                }
            }

            if (result.trafficFlows) {
                if (index > 0) {
                    AppLogger.i(TAG, "Автоматическое переключение на рабочий узел: ${server.tag}")
                }
                return server to true
            }
            AppLogger.w(TAG, "Узел ${server.tag}: трафик не подтверждён, пробуем следующий узел...")
        }
        return null to coreStarted
    }

    private class TunnelAttempt(val coreStarted: Boolean, val trafficFlows: Boolean)

    private fun isCurrentTunnel(pfd: ParcelFileDescriptor): Boolean {
        return vpnInterface === pfd && _vpnState.value == VpnState.CONNECTING
    }

    private suspend fun attemptTunnel(server: VlessServer, pfd: ParcelFileDescriptor): TunnelAttempt {
        if (!isCurrentTunnel(pfd)) {
            return TunnelAttempt(coreStarted = false, trafficFlows = false)
        }
        AppLogger.i(
            TAG,
            "Xray candidate ${server.tag}: network=${server.network}, security=${server.security}, " +
                "pbk_present=${server.publicKey.isNotBlank()}, sni_present=${server.serverName.isNotBlank()}, " +
                "sid_present=${server.shortId.isNotBlank()}, flow=${if (server.flow.isBlank()) "flowless" else "vision"}"
        )
        val (started, errorMsg) = xrayController?.startXray(server, pfd.fd) ?: Pair(false, "Unknown Error")
        if (!isCurrentTunnel(pfd)) {
            return TunnelAttempt(coreStarted = started, trafficFlows = false)
        }
        if (!started) {
            AppLogger.w(TAG, "Узел ${server.tag} не запустился: ${errorMsg ?: "неизвестная ошибка"}")
            return TunnelAttempt(coreStarted = false, trafficFlows = false)
        }
        val flows = waitForTraffic()
        if (!flows) {
            AppLogger.w(TAG, "Узел ${server.tag}: туннель поднят, но данные не проходят")
        }
        return TunnelAttempt(coreStarted = true, trafficFlows = flows)
    }

    private suspend fun waitForTraffic(): Boolean {
        val controller = xrayController ?: return false
        if (controller.verifyDataPlane(6000)) return true
        delay(500)
        return controller.verifyDataPlane(4000)
    }

    private fun startSessionTimer(initialSeconds: Long = _sessionSeconds.value) {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            var seconds = initialSeconds
            while (isActive && _vpnState.value == VpnState.CONNECTED) {
                delay(1000)
                seconds++
                _sessionSeconds.value = seconds

                val minutes = seconds / 60
                val remSeconds = seconds % 60
                val durationStr = String.format("%02d:%02d", minutes, remSeconds)

                val speed = _activeSpeed.value
                notificationManager.updateConnected(
                    pingMs = _activePing.value,
                    durationStr = durationStr,
                    downBps = speed.downBps,
                    upBps = speed.upBps,
                    isSpeedEnabled = (settingsRepository.speedIntervalSeconds > 0)
                )
            }
        }
    }

    private fun startPeriodicPing(server: VlessServer) {
        pingJob?.cancel()
        pingJob = serviceScope.launch {
            while (isActive && _vpnState.value == VpnState.CONNECTED) {
                val intervalSec = settingsRepository.autoPingIntervalSeconds
                if (intervalSec <= 0) {
                    delay(1000)
                    continue
                }
                delay(intervalSec * 1000L)
                if (!isActive || _vpnState.value != VpnState.CONNECTED) break

                val ping = pingRepository.measurePing(server, timeoutMs = 2000) { socket ->
                    try {
                        protect(socket)
                    } catch (t: Throwable) {
                        Log.w(TAG, "protect(socket) failed: ${t.message}")
                    }
                }
                if (ping in 1..9998) {
                    val current = _activePing.value
                    if (current in 1..9998) {
                        _activePing.value = (current * 0.65 + ping * 0.35).toLong()
                    } else {
                        _activePing.value = ping
                    }
                }
            }
        }
    }

    private fun startSpeedMonitoring() {
        speedJob?.cancel()
        speedJob = serviceScope.launch {
            val myUid = android.os.Process.myUid()
            var lastRx = TrafficStats.getUidRxBytes(myUid)
            var lastTx = TrafficStats.getUidTxBytes(myUid)
            var useTotal = false
            if (lastRx == TrafficStats.UNSUPPORTED.toLong() || lastTx == TrafficStats.UNSUPPORTED.toLong()) {
                lastRx = TrafficStats.getTotalRxBytes()
                lastTx = TrafficStats.getTotalTxBytes()
                useTotal = true
            }
            var lastTime = System.currentTimeMillis()

            while (isActive && _vpnState.value == VpnState.CONNECTED) {
                val intervalSec = settingsRepository.speedIntervalSeconds
                if (intervalSec <= 0) {
                    _activeSpeed.value = SpeedInfo(0L, 0L, isEnabled = false)
                    delay(1000)
                    lastRx = if (useTotal) TrafficStats.getTotalRxBytes() else TrafficStats.getUidRxBytes(myUid)
                    lastTx = if (useTotal) TrafficStats.getTotalTxBytes() else TrafficStats.getUidTxBytes(myUid)
                    lastTime = System.currentTimeMillis()
                    continue
                }

                delay(intervalSec * 1000L)
                if (!isActive || _vpnState.value != VpnState.CONNECTED) break

                val now = System.currentTimeMillis()
                val currentRx = if (useTotal) TrafficStats.getTotalRxBytes() else TrafficStats.getUidRxBytes(myUid)
                val currentTx = if (useTotal) TrafficStats.getTotalTxBytes() else TrafficStats.getUidTxBytes(myUid)

                val dtSec = (now - lastTime) / 1000.0
                if (dtSec > 0 && currentRx >= lastRx && currentTx >= lastTx) {
                    val downBps = ((currentRx - lastRx) / dtSec).toLong().coerceAtLeast(0L)
                    val upBps = ((currentTx - lastTx) / dtSec).toLong().coerceAtLeast(0L)
                    _activeSpeed.value = SpeedInfo(downBps, upBps, isEnabled = true)
                }

                lastRx = currentRx
                lastTx = currentTx
                lastTime = now
            }
        }
    }

    private fun stopVpn(cancelConnectionJob: Boolean = true, stopService: Boolean = true) {
        _vpnState.value = VpnState.DISCONNECTING
        unregisterNetworkMonitoring()
        if (cancelConnectionJob) connectionJob?.cancel()
        pingJob?.cancel()
        timerJob?.cancel()
        speedJob?.cancel()

        try {
            xrayController?.stopXray()
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping Xray: ${e.message}")
        }

        val interfaceToClose = vpnInterface
        vpnInterface = null
        try {
            interfaceToClose?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        }

        stopForegroundCompat(true)
        if (settingsRepository.isStatusNotificationEnabled) {
            notificationManager.showDisconnected()
        } else {
            notificationManager.cancelNotification()
        }

        _vpnState.value = VpnState.DISCONNECTED
        _activePing.value = -1L
        _sessionSeconds.value = 0L
        _activeSpeed.value = SpeedInfo(0L, 0L, isEnabled = false)
        _activeServer.value = null
        _tunnelHealthy.value = false
        AppLogger.i(TAG, "VPN disconnected. Tunnel closed and session ended.")

        if (stopService) stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    private fun stopForegroundCompat(removeNotification: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    private fun registerNetworkMonitoring() {
        unregisterNetworkMonitoring()
        try {
            val cm = connectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (lastKnownNetwork != null && lastKnownNetwork != network && _vpnState.value == VpnState.CONNECTED) {
                        AppLogger.i(TAG, "Смена активной сети (Handover). Перезапуск туннеля...")
                        handleNetworkHandover(network)
                    }
                    lastKnownNetwork = network
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        try {
                            setUnderlyingNetworks(arrayOf(network))
                        } catch (e: Throwable) {
                            Log.w(TAG, "setUnderlyingNetworks failed: ${e.message}")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    if (lastKnownNetwork == network) {
                        lastKnownNetwork = null
                        if (_vpnState.value == VpnState.CONNECTED) {
                            if (settingsRepository.isKillSwitchEnabled) {
                                AppLogger.w(TAG, "Связь с сетью потеряна! Kill Switch активен: весь трафик удерживается в туннеле, утечка предотвращена.")
                            } else {
                                AppLogger.w(TAG, "Связь с сетью потеряна. Ожидание восстановления соединения...")
                            }
                        }
                    }
                }
            }
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Throwable) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkMonitoring() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        networkCallback = null
        lastKnownNetwork = null
    }

    private fun handleNetworkHandover(newNetwork: Network) {
        if (_vpnState.value != VpnState.CONNECTED && _vpnState.value != VpnState.CONNECTING) return
        serviceScope.launch {
            try {
                if (settingsRepository.isKillSwitchEnabled) {
                    AppLogger.i(TAG, "Kill Switch: переключение туннеля на новую сеть без разрыва интерфейса (Zero Leak)...")
                }
                // Stop Xray on old socket
                xrayController?.stopXray()
                delay(200)

                // Update underlying network for VPN
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    try {
                        setUnderlyingNetworks(arrayOf(newNetwork))
                    } catch (_: Throwable) {}
                }

                val pfd = vpnInterface
                val server = _activeServer.value ?: vlessKeyRepository.getVlessServers().firstOrNull()
                if (server != null && pfd != null && vpnInterface === pfd && _vpnState.value == VpnState.CONNECTED) {
                    val (started, errorMsg) = xrayController?.startXray(server, pfd.fd) ?: Pair(false, "Unknown")
                    val trafficOk = started && xrayController?.verifyDataPlane(6000) == true
                    if (trafficOk) {
                        _tunnelHealthy.value = true
                        _vpnState.value = VpnState.CONNECTED
                        startSessionTimer(_sessionSeconds.value)
                        startSpeedMonitoring()
                        startPeriodicPing(server)
                        AppLogger.i(TAG, "Туннель успешно переподключен на новой сети!")
                    } else {
                        AppLogger.e(TAG, "Ошибка переподключения туннеля или трафика на новой сети: $errorMsg")
                        stopVpn()
                    }
                }
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Ошибка при смене сети: ${e.message}", e)
            }
        }
    }

    private fun reconnectVpn() {
        if (_vpnState.value == VpnState.DISCONNECTED) {
            startVpn()
            return
        }
        AppLogger.i(TAG, "Запрос на переподключение VPN к выбранному серверу...")
        _vpnState.value = VpnState.CONNECTING

        val previousConnectionJob = connectionJob
        connectionJob = serviceScope.launch {
            previousConnectionJob?.cancelAndJoin()
            if (_vpnState.value != VpnState.CONNECTING) return@launch
            try {
                // 1. Stop current Xray core cleanly
                xrayController?.stopXray()
                delay(200)

                val isFrancePlan = settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE &&
                        com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()

                val newServer = if (isFrancePlan) {
                    com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.ensureFranceVlessKey()
                    val activeKey = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.getActiveVlessKey()
                    val parsedServer = if (!activeKey.isNullOrBlank()) {
                        com.naua_security_mirage.app.data.model.VlessServer.fromUri(activeKey, "Франция (Платный)")
                    } else null

                    val france = parsedServer ?: run {
                        val clientUuid = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.getActiveClientUuid()
                        val clientFlow = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.getActiveClientFlow(clientUuid)
                        com.naua_security_mirage.app.data.supabase.SupabaseConfig.getFranceServer(clientUuid, clientFlow)
                    }
                    AppLogger.i(TAG, "Выбран платный узел для переподключения: ${france.tag} (${france.address}:${france.port}) с UUID: ${france.uuid}, flow: '${france.flow}'")
                    france
                } else {
                    val servers = vlessKeyRepository.getVlessServers()
                    val measured = pingRepository.measureAllPings(servers)
                    val best = pingRepository.selectBestServer(measured)
                    AppLogger.i(TAG, "Выбран оптимальный бесплатный узел для переподключения: ${best.tag}")
                    best
                }

                val pfd = vpnInterface
                if (pfd != null) {
                    val tunnelAttempt = withTimeoutOrNull(60_000L) {
                        openWorkingTunnel(newServer, isFrancePlan, pfd)
                    }
                    val active = tunnelAttempt?.first
                    if (active != null) {
                        _tunnelHealthy.value = true
                        _activeServer.value = active
                        _vpnState.value = VpnState.CONNECTED
                        _activePing.value = active.pingMs
                        startSessionTimer(_sessionSeconds.value)
                        startSpeedMonitoring()
                        startPeriodicPing(active)

                        val minutes = _sessionSeconds.value / 60
                        val remSeconds = _sessionSeconds.value % 60
                        val durationStr = String.format("%02d:%02d", minutes, remSeconds)
                        val speed = _activeSpeed.value
                        notificationManager.updateConnected(
                            pingMs = _activePing.value,
                            durationStr = durationStr,
                            downBps = speed.downBps,
                            upBps = speed.upBps,
                            isSpeedEnabled = (settingsRepository.speedIntervalSeconds > 0)
                        )
                        AppLogger.i(TAG, "VPN успешно переподключен на узел: ${active.tag}")
                    } else {
                        AppLogger.e(TAG, "Ни один узел не пропускает трафик при переподключении, полный перезапуск...")
                        stopVpn(cancelConnectionJob = false, stopService = false)
                        delay(300)
                        startVpn()
                    }
                } else {
                    stopVpn(cancelConnectionJob = false, stopService = false)
                    delay(300)
                    startVpn()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Ошибка при переподключении: ${e.message}", e)
                stopVpn(cancelConnectionJob = false, stopService = false)
                delay(300)
                startVpn()
            }
        }
    }

    companion object {
        private const val TAG = "MirageVpnService"

        const val ACTION_CONNECT = "com.naua_security_mirage.app.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.naua_security_mirage.app.ACTION_DISCONNECT"
        const val ACTION_RECONNECT = "com.naua_security_mirage.app.ACTION_RECONNECT"

        private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        private val _activePing = MutableStateFlow(-1L)
        val activePing: StateFlow<Long> = _activePing.asStateFlow()

        private val _sessionSeconds = MutableStateFlow(0L)
        val sessionSeconds: StateFlow<Long> = _sessionSeconds.asStateFlow()

        private val _activeServer = MutableStateFlow<VlessServer?>(null)
        val activeServer: StateFlow<VlessServer?> = _activeServer.asStateFlow()

        /** True only when the data plane was verified by a real request through the tunnel. */
        private val _tunnelHealthy = MutableStateFlow(false)
        val tunnelHealthy: StateFlow<Boolean> = _tunnelHealthy.asStateFlow()

        private val _activeSpeed = MutableStateFlow(SpeedInfo())
        val activeSpeed: StateFlow<SpeedInfo> = _activeSpeed.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, MirageVpnService::class.java).apply {
                action = ACTION_CONNECT
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            MirageTileService.requestUpdate(context)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MirageVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
            MirageTileService.requestUpdate(context)
        }

        fun reconnect(context: Context) {
            val intent = Intent(context, MirageVpnService::class.java).apply {
                action = ACTION_RECONNECT
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            MirageTileService.requestUpdate(context)
        }
    }
}
