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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            try {
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
                val bestServer = pingRepository.selectBestServer(measuredServers)
                val bestPingText = if (bestServer.pingMs in 1..9998) "${bestServer.pingMs} ms" else "доступен"
                AppLogger.i(TAG, "Выбран оптимальный узел: ${bestServer.tag} (задержка: $bestPingText)")
                Log.d(TAG, "Selected best server: ${bestServer.tag} ($bestPingText)")

                // 4. Establish Android VPN Tun interface (IPv4 default route)
                val builder = Builder()
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("77.88.8.8")
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(1420)
                    .setSession("Mirage VPN")

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
                    stopVpn()
                    return@launch
                }
                vpnInterface = pfd
                AppLogger.i(TAG, "VPN Tun interface established successfully (FD: ${pfd.fd})")

                // 5. Start Xray core with TUN fd
                val (started, errorMsg) = xrayController?.startXray(bestServer, pfd.fd) ?: Pair<Boolean, String?>(false, "Unknown Error")
                if (!started) {
                    val finalError = errorMsg ?: "Unknown error"
                    AppLogger.e(TAG, "Failed to start Xray core: $finalError")
                    Log.e(TAG, "Failed to start Xray core: $finalError")
                    stopVpn()
                    return@launch
                }

                // 6. Update state to CONNECTED
                _activeServer.value = bestServer
                _activePing.value = bestServer.pingMs
                _sessionSeconds.value = 0L
                _vpnState.value = VpnState.CONNECTED
                AppLogger.i(TAG, "VPN State changed to CONNECTED. Маршрутизация защищенного трафика через ${bestServer.tag} (Endpoint: Зашифрован)")

                // 7. Register network monitoring callback for handover / zero-leak reconnect
                registerNetworkMonitoring()

                // 8. Launch session timer
                startSessionTimer()

                // 9. Launch periodic ping test
                startPeriodicPing(bestServer)

                // 10. Launch real-time speed monitoring
                startSpeedMonitoring()

            } catch (e: Throwable) {
                Log.e(TAG, "Error starting VPN: ${e.message}", e)
                stopVpn()
            }
        }
    }

    private fun startSessionTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            var seconds = 0L
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

    private fun stopVpn() {
        _vpnState.value = VpnState.DISCONNECTING
        unregisterNetworkMonitoring()
        connectionJob?.cancel()
        pingJob?.cancel()
        timerJob?.cancel()
        speedJob?.cancel()

        try {
            xrayController?.stopXray()
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping Xray: ${e.message}")
        }

        try {
            vpnInterface?.close()
            vpnInterface = null
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
        AppLogger.i(TAG, "VPN disconnected. Tunnel closed and session ended.")

        stopSelf()
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
                if (server != null && pfd != null) {
                    val (started, errorMsg) = xrayController?.startXray(server, pfd.fd) ?: Pair(false, "Unknown")
                    if (started) {
                        _vpnState.value = VpnState.CONNECTED
                        AppLogger.i(TAG, "Туннель успешно переподключен на новой сети!")
                    } else {
                        AppLogger.e(TAG, "Ошибка переподключения туннеля на новой сети: $errorMsg")
                    }
                }
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Ошибка при смене сети: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "MirageVpnService"

        const val ACTION_CONNECT = "com.naua_security_mirage.app.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.naua_security_mirage.app.ACTION_DISCONNECT"

        private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        private val _activePing = MutableStateFlow(-1L)
        val activePing: StateFlow<Long> = _activePing.asStateFlow()

        private val _sessionSeconds = MutableStateFlow(0L)
        val sessionSeconds: StateFlow<Long> = _sessionSeconds.asStateFlow()

        private val _activeServer = MutableStateFlow<VlessServer?>(null)
        val activeServer: StateFlow<VlessServer?> = _activeServer.asStateFlow()

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
    }
}
