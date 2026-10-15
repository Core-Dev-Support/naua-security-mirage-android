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
import com.naua_security_mirage.app.R
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket
import javax.net.ssl.SSLSocket

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
    private var watchdogJob: Job? = null
    private var recoveryJob: Job? = null
    @Volatile
    private var dropRecoveryAttempts = 0
    @Volatile
    private var userStopped = false

    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var notificationManager: VpnNotificationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var vlessKeyRepository: VlessKeyRepository
    private lateinit var pingRepository: PingRepository
    private var xrayController: XrayVpnController? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastKnownNetwork: Network? = null
    @Volatile
    private var lastPrivateDnsWarningAt = 0L

    @Volatile
    private var aiSkipCount = 0

     
    private var establishedTunMtu: Int = 0

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
                userStopped = true
                dropRecoveryAttempts = 0
                recoveryJob?.cancel()
                stopVpn()
            }
            ACTION_RECONNECT -> {
                reconnectVpn()
            }
            ACTION_CONNECT -> {
                if (_vpnState.value == VpnState.DISCONNECTED) {
                    if (prepare(this) != null) {

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
        val initialNotification = notificationManager.buildConnectingNotification()
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
        if (LockdownInspector.isOurAppLocked(this)) {
            AppLogger.w(TAG, "Включена системная блокировка сети без VPN: прямые проверки идут только через туннель")
        }
        captureUnderlyingNetwork()

        val previousConnectionJob = connectionJob
        connectionJob = serviceScope.launch {

            previousConnectionJob?.cancelAndJoin()
            if (_vpnState.value != VpnState.CONNECTING) return@launch
            captureUnderlyingNetwork()
            val isCellular = isCellularNetwork()
            AppLogger.i(TAG, "Тип сети на старте: ${describeUnderlyingNetwork()}")
            warmDnsSelection()
            try {
                val selectedPlan = settingsRepository.selectedServerPlan
                val hasSubscription =
                    com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()
                val isFrancePlan = selectedPlan == SettingsRepository.PLAN_PREMIUM_FRANCE && hasSubscription
                val isBelarusPlan = selectedPlan == SettingsRepository.PLAN_PREMIUM_BELARUS && hasSubscription
                val isPaidPlan = settingsRepository.isPaidPlan(selectedPlan) && hasSubscription

                var premeasuredFree: List<VlessServer> = emptyList()
                var franceRefresh: Deferred<String?>? = null
                var freeLoadedCount = 0
                var freeAnyReachable = false
                var freeAiMode = false
                var freePanelReachable = false
                val bestServer = if (isFrancePlan) {
                    val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance

                    franceRefresh = serviceScope.async(Dispatchers.IO) { manager.ensureFranceVlessKey() }
                    val liveKey = withTimeoutOrNull(FRANCE_KEY_BUDGET_MS) { franceRefresh.await() }
                    if (liveKey.isNullOrBlank()) {
                        AppLogger.w(
                            TAG,
                            "Панель не ответила за ${FRANCE_KEY_BUDGET_MS}мс, используется кэшированный ключ; " +
                                    "обновление продолжится в фоне"
                        )
                    }
                    val activeKey = manager.getActiveVlessKey()
                    val parsedServer = if (!activeKey.isNullOrBlank()) {
                        com.naua_security_mirage.app.data.model.VlessServer.fromUri(activeKey, "Франция (Платный)")
                    } else null


                    val france = parsedServer ?: run {
                        val clientUuid = manager.getActiveClientUuid()
                        if (!com.naua_security_mirage.app.data.supabase.FranceAccessPolicy
                                .mayBuildPaidTunnel(clientUuid)
                        ) {
                            AppLogger.w(
                                TAG,
                                "У сервера нет ключа для этого аккаунта, платный узел пропущен"
                            )
                            null
                        } else {
                            val clientFlow = manager.getActiveClientFlow(clientUuid)
                            com.naua_security_mirage.app.data.supabase.SupabaseConfig
                                .getFranceServer(clientUuid, clientFlow)
                        }
                    }
                    val franceReady = france?.let { applyFingerprintPreference(it) }

                    franceReady?.let {
                        AppLogger.i(
                            TAG,
                            "Выбран платный узел: ${it.tag} (${it.address}:${it.port}), " +
                                    "flow: '${it.flow}', uuid: ${redactUuid(it.uuid)}"
                        )
                    }
                    franceReady
                } else if (isBelarusPlan) {
                    resolveBelarusServer()
                } else {

                    val servers = vlessKeyRepository.getVlessServers()
                    freeLoadedCount = servers.size
                    freePanelReachable = vlessKeyRepository.lastFetchPanelReachable
                    AppLogger.i(TAG, "Loaded ${servers.size} servers. Measuring latency...")
                    Log.d(TAG, "Loaded ${servers.size} servers. Measuring latency...")

                    val pingBudgetMs = if (isCellular) 12_000L else 4_500L
                    val pingProbeMs = if (isCellular) 2_000 else 700
                    val measuredServers = pingRepository.measureAllPings(
                        servers,
                        probeTimeoutMs = pingProbeMs,
                        totalBudgetMs = pingBudgetMs
                    )
                    measuredServers.forEach { s ->
                        val pingText = if (s.pingMs in 1..9998) "${s.pingMs} ms" else "таймаут"
                        AppLogger.d(TAG, "Узел ${s.tag} -> задержка: $pingText")
                    }

                    val aiModeOn = settingsRepository.isAiModeEnabled
                    freeAiMode = aiModeOn
                    aiSkipCount = 0
                    val candidates = if (aiModeOn) {
                        val allowed = AiRegionBlock.filter(measuredServers)
                        if (allowed.size < measuredServers.size) {
                            AppLogger.i(TAG, "Режим ИИ: из выбора убрано ${measuredServers.size - allowed.size} узлов из заблокированных регионов")
                        }
                        allowed
                    } else {
                        measuredServers
                    }

                    val best = pingRepository.selectBestServer(candidates)
                    val bestPingText = if (best.pingMs in 1..9998) "${best.pingMs} ms" else "доступен"
                    AppLogger.i(TAG, "Выбран оптимальный узел: ${best.tag} (задержка: $bestPingText)")
                    freeAnyReachable = measuredServers.any { it.pingMs in 1..9998 }

                    premeasuredFree = measuredServers
                    best
                }


                if (isPaidPlan && bestServer == null) {
                    AppLogger.e(TAG, "Платный узел недоступен, соединение не устанавливается")
                    AppLogger.onUserMessage(getString(R.string.paid_unavailable_later))
                    _vpnState.value = VpnState.DISCONNECTED
                    return@launch
                }
                val selectedServer = bestServer!!


                captureUnderlyingNetwork()

                establishedTunMtu = currentTunMtu()
                val builder = Builder()
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(establishedTunMtu)
                    .setSession("Mirage VPN")

                AppLogger.i(
            TAG,
            "Физическая сеть: ${describeUnderlyingNetwork()}, " +
                    "MTU туннеля: $establishedTunMtu (${mtuReason(currentNetworkCapabilities())})"
        )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                if (settingsRepository.isKillSwitchEnabled) {
                    builder.setBlocking(true)
                    val locked = LockdownInspector.isOurAppLocked(this@MirageVpnService)
                    AppLogger.i(TAG, "Защита от утечек: TUN в блокирующем режиме, системная блокировка ${if (locked) "активна" else "выключена"}")
                }

                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Throwable) {
                    Log.w(TAG, "addDisallowedApplication self failed: ${e.message}")
                }

                val bypassed = settingsRepository.bypassedAppPackages
                if (bypassed.isNotEmpty()) {
                    AppLogger.i(TAG, "Раздельное туннелирование: исключено приложений из VPN: ${bypassed.size}")
                    if (AI_APP_PACKAGES.any { bypassed.contains(it) }) {
                        AppLogger.w(TAG, "Приложение ИИ исключено из VPN: его проверки увидят прямой IP")
                    }
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

                val onPaidNode = isPaidPlan
                val tunnelBudgetMs = if (onPaidNode) 90_000L else 60_000L
                var tunnelAttempt = withTimeoutOrNull(tunnelBudgetMs) {
                    openWorkingTunnel(selectedServer, onPaidNode, pfd, premeasuredFree)
                }


                if (tunnelAttempt?.first == null && onPaidNode && franceRefresh != null && !franceRefresh.isCompleted) {
                    val freshKey = withTimeoutOrNull(FRANCE_KEY_BUDGET_MS) { franceRefresh.await() }
                    val retryServer = freshKey
                        ?.let { com.naua_security_mirage.app.data.model.VlessServer.fromUri(it, "Франция (Платный)") }
                        ?.let { applyFingerprintPreference(it) }
                    if (retryServer != null) {
                        AppLogger.w(TAG, "Повтор платного подключения с ключом, полученным после обновления панели")
                        tunnelAttempt = withTimeoutOrNull(tunnelBudgetMs) {
                            openWorkingTunnel(retryServer, true, pfd, emptyList())
                        }
                    }
                }
                if (tunnelAttempt == null) {
                    AppLogger.e(TAG, "Превышен лимит времени проверки VPN-узлов — VPN отключается.")
                    stopVpn(cancelConnectionJob = false)
                    return@launch
                }
                val (workingServer, _) = tunnelAttempt
                if (workingServer == null) {
                    if (LockdownInspector.isOurAppLocked(this@MirageVpnService)) {
                        AppLogger.e(TAG, "Подключение невозможно: включена системная блокировка сети без VPN")
                        AppLogger.onUserMessage(getString(R.string.vpn_error_lockdown))
                    } else if (isPaidPlan) {
                        AppLogger.e(TAG, "Платный узел не подтвердил передачу трафика — VPN отключается.")
                        AppLogger.onUserMessage(getString(R.string.paid_unavailable_retry))
                    } else {
                        AppLogger.e(TAG, "Ни один узел не подтвердил передачу трафика — VPN отключается.")
                        val reason = when {
                            freeAiMode && aiSkipCount > 0 -> getString(R.string.vpn_error_ai_no_nodes)
                            freePanelReachable -> getString(R.string.vpn_error_panel_empty)
                            freeLoadedCount == 0 -> getString(R.string.vpn_error_no_internet)
                            !freeAnyReachable -> getString(R.string.vpn_error_nodes_blocked)
                            else -> getString(R.string.vpn_error_no_traffic)
                        }
                        AppLogger.onUserMessage(reason)
                    }
                    stopVpn(cancelConnectionJob = false)
                    return@launch
                }
                _tunnelHealthy.value = true
                val activeServer = workingServer

                _activeServer.value = activeServer
                _sessionSeconds.value = 0L
                _vpnState.value = VpnState.CONNECTED
                AppLogger.i(TAG, "VPN State changed to CONNECTED. Маршрутизация защищенного трафика через ${activeServer.tag} (Endpoint: Зашифрован)")
                notificationManager.updateConnected(
                    pingMs = activeServer.pingMs,
                    durationStr = "00:00",
                    downBps = 0L,
                    upBps = 0L,
                    isSpeedEnabled = (settingsRepository.speedIntervalSeconds > 0)
                )

                serviceScope.launch(Dispatchers.IO) { checkPrivateDnsCompatibility(pfd) }


                if (activeServer.pingMs in 1..9998) {
                    _activePing.value = activeServer.pingMs
                } else {
                    serviceScope.launch {
                        val measured = pingRepository.measurePing(activeServer, timeoutMs = 1200) { socket ->
                            try {
                                protect(socket)
                            } catch (t: Throwable) {
                                Log.w(TAG, "protect(socket) failed during initial ping: ${t.message}")
                            }
                        }
                        if (measured in 1..9998 && isCurrentTunnel(pfd)) {
                            _activePing.value = measured
                        }
                    }
                }

                registerNetworkMonitoring()

                dropRecoveryAttempts = 0
                userStopped = false

                startSessionTimer()

                startPeriodicPing(activeServer)

                startSpeedMonitoring()

                startTunnelWatchdog(activeServer)

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Error starting VPN: ${e.message}", e)
                stopVpn(cancelConnectionJob = false)
            }
        }
    }

    private suspend fun resolveBelarusServer(): VlessServer? {
        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
        val key = manager.ensureBelarusVlessKey()
        val server = if (!key.isNullOrBlank()) {
            com.naua_security_mirage.app.data.model.VlessServer.fromUri(
                key,
                com.naua_security_mirage.app.data.supabase.SupabaseConfig.BELARUS_LABEL
            )
        } else {
            null
        }
        if (server == null) {
            AppLogger.w(TAG, "belarus-config не выдал ключ, узел Беларусь пропущен")
            return null
        }
        if (!com.naua_security_mirage.app.data.supabase.FranceAccessPolicy
                .mayBuildPaidTunnel(server.uuid)
        ) {
            AppLogger.w(TAG, "У узла Беларусь некорректный UUID, платный узел пропущен")
            return null
        }
        AppLogger.i(
            TAG,
            "Выбран платный узел: ${server.tag} (${server.address}:${server.port}), " +
                    "network=${server.network}, security=${server.security}, " +
                    "uuid: ${redactUuid(server.uuid)}"
        )
        return server
    }

    private suspend fun openWorkingTunnel(
        preferred: VlessServer,
        isPaidPlan: Boolean,
        pfd: ParcelFileDescriptor,
        premeasuredFree: List<VlessServer> = emptyList()
    ): Pair<VlessServer?, Boolean> {
        val candidates = mutableListOf(preferred)

        val isFranceNode = isPaidPlan &&
                preferred.address.equals(
                    com.naua_security_mirage.app.data.supabase.SupabaseConfig.FRANCE_HOST,
                    ignoreCase = true
                )

        if (isFranceNode) {
            try {
                val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                val clientUuid = manager.getActiveClientUuid()
                if (!com.naua_security_mirage.app.data.supabase.FranceAccessPolicy.mayBuildPaidTunnel(clientUuid)) {
                    AppLogger.w(TAG, "Резервные входы платного узла пропущены: у сервера нет ключа для аккаунта")
                } else {
                    for (extra in com.naua_security_mirage.app.data.supabase.SupabaseConfig.getFranceServers(clientUuid)
                            .drop(1)) {
                        if (candidates.none { it.address == extra.address && it.port == extra.port }) {
                            candidates += extra
                            AppLogger.i(TAG, "Добавлен резервный вход платного узла: ${extra.address}:${extra.port}")
                        }
                    }
                }
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Не удалось разобрать список входов платного узла: ${e.message}")
            }
        }


        if (!isPaidPlan && premeasuredFree.isNotEmpty()) {
            appendFreeCandidates(candidates, premeasuredFree)
        }

        var coreStarted = false
        for ((index, candidate) in candidates.withIndex()) {
            val server = candidate

            if (isPaidPlan && server.tag.startsWith(PAID_TAG_PREFIX)) {

                serviceScope.launch { checkEndpointReachability(server) }
            }

            val result = attemptTunnel(server, pfd)
            coreStarted = coreStarted || result.coreStarted
            if (!isCurrentTunnel(pfd)) return null to coreStarted

            if (result.trafficFlows && acceptCandidateForAiMode(server, isPaidPlan)) {
                if (index > 0) {
                    AppLogger.i(TAG, "Автоматическое переключение на рабочий узел: ${server.tag}")
                }
                return server to true
            }
            AppLogger.w(TAG, "Узел ${server.tag}: трафик не подтверждён, пробуем следующий узел...")
        }

        if (!isPaidPlan && premeasuredFree.isEmpty()) {
            val freeServers = loadFreeCandidates()
            for (server in freeServers) {
                if (candidates.any { it.address == server.address && it.port == server.port && it.uuid == server.uuid }) {
                    continue
                }
                candidates += server
                val result = attemptTunnel(server, pfd)
                coreStarted = coreStarted || result.coreStarted
                if (!isCurrentTunnel(pfd)) return null to coreStarted
                if (result.trafficFlows && acceptCandidateForAiMode(server, isPaidPlan)) {
                    AppLogger.i(TAG, "Автоматическое переключение на рабочий узел: ${server.tag}")
                    return server to true
                }
                AppLogger.w(TAG, "Узел ${server.tag}: трафик не подтверждён, пробуем следующий узел...")
            }
        }

        if (!isPaidPlan && coreStarted) {
            val reachable = candidates.filter { it.pingMs in 1..9998 }
            if (reachable.isNotEmpty()) {
                AppLogger.w(TAG, "Все узлы пройдены без трафика, повторная попытка по отвечающим узлам...")
                delay(1500)
                for (server in reachable) {
                    if (!isCurrentTunnel(pfd)) return null to coreStarted
                    val result = attemptTunnel(server, pfd)
                    coreStarted = coreStarted || result.coreStarted
                    if (!isCurrentTunnel(pfd)) return null to coreStarted
                    if (result.trafficFlows && acceptCandidateForAiMode(server, isPaidPlan)) {
                        AppLogger.i(TAG, "Узел ${server.tag} заработал со второй попытки")
                        return server to true
                    }
                    AppLogger.w(TAG, "Узел ${server.tag}: повторная попытка без трафика")
                }
            }
        }
        if (!isPaidPlan) {
            val rotated = rotateFreeKeyAndRetry(candidates, pfd, coreStarted)
            if (rotated?.first != null) return rotated.first to true
            coreStarted = coreStarted || (rotated?.second ?: false)
        }
        return null to coreStarted
    }

    private suspend fun rotateFreeKeyAndRetry(
        candidates: MutableList<VlessServer>,
        pfd: ParcelFileDescriptor,
        coreStarted: Boolean
    ): Pair<VlessServer?, Boolean>? {
        val triedKeys = candidates.map { it.uuid }.toMutableSet()
        val fresh = try {
            vlessKeyRepository.getVlessServers(forceRefresh = true)
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Не удалось обновить ключ бесплатных узлов: ${e.message}")
            emptyList()
        }
        val unseenKeys = fresh.filter { !triedKeys.contains(it.uuid) }
        if (unseenKeys.isEmpty()) {
            AppLogger.w(TAG, "Панель верла прежний ключ, смены идентификатора нет")
            return null
        }

        AppLogger.w(
            TAG,
            "Узлы на прежнем ключе исчерпаны, пробуем ${unseenKeys.size} на новом идентификаторе"
        )
        var anyStarted = coreStarted
        for (server in unseenKeys) {
            if (!isCurrentTunnel(pfd)) return null to anyStarted
            candidates += server
            val result = attemptTunnel(server, pfd)
            anyStarted = anyStarted || result.coreStarted
            if (!isCurrentTunnel(pfd)) return null to anyStarted
            if (result.trafficFlows && acceptCandidateForAiMode(server, isPaidPlan = false)) {
                AppLogger.i(TAG, "Узел ${server.tag} заработал на новом идентификаторе")
                return server to anyStarted
            }
            AppLogger.w(TAG, "Узел ${server.tag} на новом идентификаторе: трафик не подтверждён")
        }
        return null to anyStarted
    }

    private suspend fun checkPrivateDnsCompatibility(pfd: ParcelFileDescriptor) {
        delay(1500)
        if (!isCurrentTunnel(pfd)) return

        val state = PrivateDnsInspector.read(this)
        AppLogger.i(TAG, "Частный DNS: ${PrivateDnsInspector.describe(state)}")

        val enabled = state.mode == PrivateDnsInspector.Mode.STRICT ||
                state.mode == PrivateDnsInspector.Mode.OPPORTUNISTIC
        if (!enabled) return

        val now = System.currentTimeMillis()
        if (now - lastPrivateDnsWarningAt < PRIVATE_DNS_WARNING_COOLDOWN_MS) return
        lastPrivateDnsWarningAt = now
        AppLogger.w(TAG, "Частный DNS включён — рекомендуем Выкл. или Автоматически")
        AppLogger.onUserMessage(getString(R.string.private_dns_conflict))
    }

    private suspend fun loadFreeCandidates(): List<VlessServer> {
        return try {
            val freeServers = vlessKeyRepository.getVlessServers()
            val ordered = if (freeServers.size > 1) {
                pingRepository.measureAllPings(freeServers)
                    .sortedBy { if (it.pingMs in 1..9998) it.pingMs else Long.MAX_VALUE }
            } else {
                freeServers
            }
            AppLogger.i(TAG, "Подготовлено резервных бесплатных узлов: ${ordered.size}")
            ordered
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Не удалось загрузить резервные бесплатные узлы: ${e.message}")
            emptyList()
        }
    }

    private fun appendFreeCandidates(candidates: MutableList<VlessServer>, freeServers: List<VlessServer>) {
        for (server in freeServers) {
            if (candidates.none { it.address == server.address && it.port == server.port && it.uuid == server.uuid }) {
                candidates += server
            }
        }
        AppLogger.i(TAG, "Подготовлено кандидатов для failover: ${candidates.size}")
    }

    private class TunnelAttempt(val coreStarted: Boolean, val trafficFlows: Boolean)

    private suspend fun acceptCandidateForAiMode(server: VlessServer, isPaidPlan: Boolean): Boolean {
        if (!settingsRepository.isAiModeEnabled || isPaidPlan) return true
        val country = detectExitCountryCode()
        if (country == null) {
            AppLogger.w(TAG, "Режим ИИ: страну выхода узла ${server.tag} определить не удалось, оставляем узел")
            return true
        }
        AppLogger.i(TAG, "Режим ИИ: узел ${server.tag} выходит в $country")
        if (AI_BLOCKED_COUNTRY_CODES.contains(country)) {
            AppLogger.w(TAG, "Режим ИИ: $country не поддерживается ИИ-сервисами, узел ${server.tag} пропускаем")
            aiSkipCount++
            return false
        }
        val probeTimeout = if (isCellularNetwork()) 12_000 else 9_000
        val access = try {
            xrayController?.probeGeminiApiAccess(probeTimeout)
        } catch (_: Throwable) {
            null
        }
        when (access) {
            true -> {
                AppLogger.i(TAG, "Режим ИИ: Google отвечает узлу ${server.tag}, оставляем")
                return true
            }
            false -> {
                AppLogger.w(TAG, "Режим ИИ: Google отклоняет узел ${server.tag}, пропускаем")
                aiSkipCount++
                return false
            }
            else -> {
                AppLogger.w(TAG, "Режим ИИ: проверка Google для ${server.tag} не удалась, оставляем узел")
                return true
            }
        }
    }

    private suspend fun detectExitCountryCode(): String? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(if (isCellularNetwork()) 12_000L else 9_000L) {
                var socket: java.net.Socket? = null
                try {
                    socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", SOCKS_PORT), 4000)
                    socket.soTimeout = 6000
                    val output = socket.getOutputStream()
                    val input = socket.getInputStream()
                    output.write(byteArrayOf(0x05, 0x01, 0x00))
                    output.flush()
                    val greeting = ByteArray(2)
                    readFully(input, greeting, 2)
                    if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                        return@withTimeoutOrNull null
                    }
                    val hostBytes = "ip-api.com".toByteArray(Charsets.US_ASCII)
                    val request = ByteArray(7 + hostBytes.size)
                    request[0] = 0x05
                    request[1] = 0x01
                    request[2] = 0x00
                    request[3] = 0x03
                    request[4] = hostBytes.size.toByte()
                    System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
                    request[5 + hostBytes.size] = 0
                    request[6 + hostBytes.size] = 80.toByte()
                    output.write(request)
                    output.flush()
                    val header = ByteArray(4)
                    readFully(input, header, 4)
                    if (header[0] != 0x05.toByte() || (header[1].toInt() and 0xFF) != 0x00) {
                        return@withTimeoutOrNull null
                    }
                    val addrLen = when (header[3].toInt()) {
                        0x01 -> 4
                        0x04 -> 16
                        0x03 -> {
                            val len = ByteArray(1)
                            readFully(input, len, 1)
                            len[0].toInt() and 0xFF
                        }
                        else -> return@withTimeoutOrNull null
                    }
                    readFully(input, ByteArray(addrLen + 2), addrLen + 2)
                    output.write("GET /json/ HTTP/1.0\r\nHost: ip-api.com\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    val text = String(input.readBytes(), Charsets.UTF_8)
                    Regex("\"countryCode\"\\s*:\\s*\"([A-Z]{2})\"").find(text)?.groupValues?.get(1)
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read <= 0) throw java.io.EOFException("SOCKS stream ended early")
            offset += read
        }
    }



    private suspend fun awaitSocksListener(): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + CORE_SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", SOCKS_PORT), 120) }
                return@withContext true
            } catch (_: Throwable) {
                delay(25)
            }
        }
        false
    }

     
    private fun applyFingerprintPreference(server: VlessServer): VlessServer {
        val proven = settingsRepository.franceFingerprintOverride ?: return server
        if (server.fingerprint.equals(proven, ignoreCase = true)) return server
        return server.copy(fingerprint = proven).also {
            AppLogger.i(TAG, "Платный узел: используем сохранённый отпечаток '$proven'")
        }
    }

    private fun isCurrentTunnel(pfd: ParcelFileDescriptor): Boolean {
        return vpnInterface === pfd && _vpnState.value == VpnState.CONNECTING
    }

    private fun redactUuid(uuid: String): String {
        if (uuid.isBlank()) return "отсутствует"
        val tail = uuid.takeLast(4)
        return "********-****-****-****-…$tail"
    }



    private suspend fun checkEndpointReachability(server: VlessServer) {
        val reachability = pingRepository.measurePing(server, timeoutMs = 3000) { socket ->
            try {
                protect(socket)
            } catch (t: Throwable) {
                Log.w(TAG, "protect(socket) failed during reachability probe: ${t.message}")
            }
        }
        if (reachability in 1..9998) {
            AppLogger.i(TAG, "Платный вход ${server.address}:${server.port} доступен с устройства (${reachability} мс)")
            probeTlsHandshake(server)
        } else {
            AppLogger.w(
                TAG,
                "Платный вход ${server.address}:${server.port} НЕДОСТУПЕН с этого устройства " +
                        "(TCP не установлен). Туннель может подняться, но данные не пройдут."
            )
        }
    }



    private fun probeTlsHandshake(server: VlessServer) {
        var raw: Socket? = null
        var tls: SSLSocket? = null
        try {
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val context = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, trustAll, java.security.SecureRandom())
            }

            raw = Socket()
            protect(raw)
            raw.connect(java.net.InetSocketAddress(server.address, server.port), 3000)

            tls = context.socketFactory.createSocket(raw, server.serverName, server.port, false) as SSLSocket
            tls.useClientMode = true
            tls.soTimeout = 5000
            tls.startHandshake()
            val peer = tls.session.peerCertificates.firstOrNull()
            AppLogger.i(
                TAG,
                "TLS до платного входа ${server.address}:${server.port} установлен " +
                        "(сертификат получен, шифр ${tls.session.cipherSuite.substringBefore('_')})"
            )
            if (peer == null) AppLogger.w(TAG, "TLS-сессия без сертификата — проверка неинформативна")
        } catch (t: Throwable) {
            AppLogger.w(
                TAG,
                "TLS до платного входа ${server.address}:${server.port} НЕ ЗАВЕРШАЕТСЯ " +
                        "(${t.javaClass.simpleName}: ${t.message}). TCP проходит, дальше тишина — " +
                        "похоже, путь обрывается на TLS-рукопожатии."
            )
        } finally {
            try { tls?.close() } catch (_: Throwable) {}
            try { raw?.close() } catch (_: Throwable) {}
        }
    }

    private var dnsWarmJob: Job? = null

    private fun warmDnsSelection() {
        if (dnsWarmJob?.isActive == true) return
        dnsWarmJob = serviceScope.launch(Dispatchers.IO) { pickDnsAutomatically() }
    }

    private suspend fun pickDnsAutomatically() {
        val settingsRepo = com.naua_security_mirage.app.data.repository.SettingsRepository(this)
        if (settingsRepo.selectedDnsId != com.naua_security_mirage.app.data.model.DnsServer.AUTO_ID) {
            if (settingsRepo.selectedDnsId.isNotBlank()) {
                AppLogger.i(TAG, "Автоматический выбор DNS пропущен, вручную выбран ${settingsRepo.selectedDnsId}")
            }
            return
        }
        val started = System.currentTimeMillis()
        val age = System.currentTimeMillis() - settingsRepo.autoDnsPickedAt
        if (settingsRepo.autoDnsPickedId.isNotBlank() &&
            (age < AUTO_DNS_TTL_MS || age < AUTO_DNS_RETRY_MS)
        ) {
            return
        }
        val winner = com.naua_security_mirage.app.util.DohTester.pickFastest(
            com.naua_security_mirage.app.data.model.DnsServer.BUILT_IN
        )
        settingsRepo.autoDnsPickedAt = System.currentTimeMillis()
        if (winner == null) {
            AppLogger.i(TAG, "Автоматический выбор DNS: годных нет, остаётся цепочка по умолчанию")
            return
        }
        settingsRepo.autoDnsPickedId = winner.server.id
        AppLogger.i(
            TAG,
            "Автоматический выбор DNS: ${winner.server.id} за ${winner.latencyMs} мс " +
                "(замер ${System.currentTimeMillis() - started} мс)"
        )
    }

    private suspend fun attemptTunnel(
        server: VlessServer,
        pfd: ParcelFileDescriptor
    ): TunnelAttempt {
        if (!isCurrentTunnel(pfd)) {
            return TunnelAttempt(coreStarted = false, trafficFlows = false)
        }
        dnsWarmJob?.let { job -> runCatching { job.join() } }
        pickDnsAutomatically()
        AppLogger.i(
            TAG,
            "Xray candidate ${server.tag}: network=${server.network}, security=${server.security}, " +
                "pbk_present=${server.publicKey.isNotBlank()}, sni_present=${server.serverName.isNotBlank()}, " +
                "sid_present=${server.shortId.isNotBlank()}, flow=${if (server.flow.isBlank()) "flowless" else "vision"}" +
                ", fp=${server.fingerprint}"
        )
        val (started, errorMsg) = xrayController?.startXray(server, pfd.fd, currentTunMtu()) ?: Pair(false, "Unknown Error")
        if (!isCurrentTunnel(pfd)) {
            return TunnelAttempt(coreStarted = started, trafficFlows = false)
        }
        if (!started) {
            AppLogger.w(TAG, "Узел ${server.tag} не запустился: ${errorMsg ?: "неизвестная ошибка"}")
            return TunnelAttempt(coreStarted = false, trafficFlows = false)
        }

        if (!awaitSocksListener()) {
            AppLogger.w(TAG, "Узел ${server.tag}: SOCKS-порт не поднялся за ${CORE_SETTLE_MS} мс")
        }
        if (!isCurrentTunnel(pfd)) {
            return TunnelAttempt(coreStarted = true, trafficFlows = false)
        }
        val flows = waitForTraffic()
        if (!flows) {
            AppLogger.w(TAG, "Узел ${server.tag}: туннель поднят, но данные не проходят")
        }
        return TunnelAttempt(coreStarted = true, trafficFlows = flows)
    }



    private suspend fun waitForTraffic(): Boolean {
        val controller = xrayController ?: return false
        val cellular = isCellularNetwork()
        val firstMs = if (cellular) 9000 else 5000
        val secondMs = if (cellular) 6000 else 3500
        if (controller.verifyDataPlane(firstMs)) return true
        delay(400)
        return controller.verifyDataPlane(secondMs)
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
                if (intervalSec <= 0) break

                delay(intervalSec * 1000L)
                if (!isActive || _vpnState.value != VpnState.CONNECTED) break

                val target = _activeServer.value ?: server
                val ping = pingRepository.measurePing(target, timeoutMs = 2000) { socket ->
                    try {
                        protect(socket)
                    } catch (t: Throwable) {
                        Log.w(TAG, "protect(socket) failed: ${t.message}")
                    }
                }
                if (ping in 1..9998) {
                    val current = _activePing.value
                    _activePing.value = if (current !in 1..9998) {
                        ping
                    } else if (ping < current) {
                        (current * 0.5 + ping * 0.5).toLong()
                    } else {
                        (current * 0.85 + ping * 0.15).toLong()
                    }
                } else {
                    AppLogger.w(TAG, "Автопинг узла ${target.tag} не ответил")
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
                    break
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

    private fun scheduleRecoveryAfterDrop() {
        if (userStopped) return
        recoveryJob?.cancel()

        dropRecoveryAttempts++
        if (dropRecoveryAttempts > MAX_DROP_RECOVERIES) {
            AppLogger.e(
                TAG,
                "Автовосстановление не помогло за $MAX_DROP_RECOVERIES попыток — отключаюсь"
            )
            AppLogger.onUserMessage(getString(R.string.tunnel_stuck))
            _tunnelHealthy.value = false
            userStopped = true
            stopVpn(cancelConnectionJob = false)
            return
        }

        val backoffMs = (DROP_RECOVERY_BACKOFF_MS * dropRecoveryAttempts).coerceAtMost(MAX_DROP_RECOVERY_BACKOFF_MS)
        AppLogger.w(
            TAG,
            "Обрыв туннеля: автовосстановление, попытка $dropRecoveryAttempts через ${backoffMs / 1000} с"
        )
        _tunnelHealthy.value = false
        _vpnState.value = VpnState.CONNECTING

        recoveryJob = serviceScope.launch {
            delay(backoffMs)
            if (!isActive || userStopped) return@launch
            stopVpn(cancelConnectionJob = false)
            delay(300)
            if (!isActive || userStopped) return@launch
            startVpn()
        }
    }

    private fun startTunnelWatchdog(server: VlessServer) {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            var failures = 0
            while (isActive && _vpnState.value == VpnState.CONNECTED) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!isActive || _vpnState.value != VpnState.CONNECTED) break

                val alive = withContext(Dispatchers.IO) {
                    xrayController?.verifyDataPlane(WATCHDOG_PROBE_MS) == true
                }
                if (alive) {
                    if (failures > 0) {
                        AppLogger.i(TAG, "Туннель ожил после $failures неудачных проверок")
                    }
                    failures = 0
                    continue
                }

                failures++
                AppLogger.w(
                    TAG,
                    "Сторож: узел ${server.tag} не передаёт трафик, неудачных проверок подряд: $failures"
                )
                if (failures >= WATCHDOG_MAX_FAILURES) {
                    AppLogger.e(
                        TAG,
                        "Сторож: туннель признан нерабочим после $failures проверок — восстанавливаю"
                    )
                    scheduleRecoveryAfterDrop()
                    return@launch
                }
            }
        }
    }

    private fun stopVpn(cancelConnectionJob: Boolean = true, stopService: Boolean = true) {
        if (_vpnState.value == VpnState.DISCONNECTED && vpnInterface == null) {
            AppLogger.d(TAG, "Повторная остановка пропущена: туннель уже закрыт")
            if (stopService) stopSelf()
            return
        }

        _vpnState.value = VpnState.DISCONNECTING
        if (settingsRepository.isStatusNotificationEnabled) {
            notificationManager.showDisconnecting()
        }
        unregisterNetworkMonitoring()
        if (cancelConnectionJob) connectionJob?.cancel()
        pingJob?.cancel()
        timerJob?.cancel()
        speedJob?.cancel()
        watchdogJob?.cancel()

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
        val finishedServer = _activeServer.value?.tag
        val finishedSeconds = _sessionSeconds.value
        if (finishedServer != null) {
            com.naua_security_mirage.app.data.SessionJournal.add(this, finishedServer, finishedSeconds)
        }
        _sessionSeconds.value = 0L
        _activeSpeed.value = SpeedInfo(0L, 0L, isEnabled = false)
        _activeServer.value = null
        _tunnelHealthy.value = false

        establishedTunMtu = 0
        AppLogger.i(TAG, "VPN disconnected. Tunnel closed and session ended.")

        if (stopService) stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }



    override fun onRevoke() {
        AppLogger.w(TAG, "Разрешение VPN отозвано системой — ядро Xray и туннель останавливаются")
        try {
            serviceScope.launch {
                stopVpn()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onRevoke cleanup failed: ${e.message}")
        }

        runCatching { xrayController?.stopXray() }
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        establishedTunMtu = 0
        _vpnState.value = VpnState.DISCONNECTED
        _tunnelHealthy.value = false
        _activeServer.value = null
        super.onRevoke()
    }

    private fun stopForegroundCompat(removeNotification: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }


    private fun captureUnderlyingNetwork() {
        try {
            val cm = connectivityManager ?: return
            lastKnownNetwork = cm.activeNetwork
        } catch (_: Throwable) {

        }
    }

    private fun isCellularNetwork(): Boolean {
        return try {
            val cm = connectivityManager ?: return false
            val caps = lastKnownNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (_: Throwable) {
            false
        }
    }

    private fun currentNetworkCapabilities(): NetworkCapabilities? {
        return try {
            val cm = connectivityManager ?: return null
            lastKnownNetwork?.let { cm.getNetworkCapabilities(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun currentTunMtu(): Int {
        val frozen = establishedTunMtu
        if (frozen > 0) return frozen
        return tunMtuFor(currentNetworkCapabilities())
    }

    private fun tunMtuFor(caps: NetworkCapabilities?): Int {
        if (caps == null) return TUN_MTU_WIFI
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return TUN_MTU_CELLULAR
        val kbps = caps.linkDownstreamBandwidthKbps
        if (kbps in 1 until WEAK_LINK_KBPS) return TUN_MTU_CELLULAR
        return TUN_MTU_WIFI
    }

    private fun mtuReason(caps: NetworkCapabilities?): String {
        if (caps == null) return "сеть не определена, базовое значение"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "мобильная сеть"
        val kbps = caps.linkDownstreamBandwidthKbps
        if (kbps in 1 until WEAK_LINK_KBPS) return "слабый канал ${kbps}kbps"
        return "стабильный канал"
    }

     
    private fun describeUnderlyingNetwork(): String {
        return try {
            val cm = connectivityManager ?: return "unknown"
            val network = lastKnownNetwork ?: return "unknown"
            val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val downstream = caps.linkDownstreamBandwidthKbps
            "$transport${if (downstream > 0) " ${downstream}kbps" else ""}"
        } catch (_: Throwable) {
            "unknown"
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
                    val previous = lastKnownNetwork

                    val switched = HandoverPolicy.shouldRestartTunnel(
                        hadPreviousNetwork = previous != null,
                        sameNetworkObject = previous == network,
                        previousStillUsable = previous?.let { isStillUsable(it) } ?: true,
                        tunnelConnected = _vpnState.value == VpnState.CONNECTED
                    )
                    if (switched) {
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
                                if (LockdownInspector.isOurAppLocked(this@MirageVpnService)) {
                                    AppLogger.w(TAG, "Связь с сетью потеряна! Системная блокировка удерживает трафик, утечка предотвращена.")
                                } else {
                                    AppLogger.w(TAG, "Связь с сетью потеряна! Системная блокировка выключена: возможна утечка вне туннеля.")
                                }
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


    private fun isStillUsable(network: Network): Boolean {
        return try {
            val cm = connectivityManager ?: return true
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {

            true
        }
    }

    private fun unregisterNetworkMonitoring() {        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        networkCallback = null
        lastKnownNetwork = null
    }

    private fun handleNetworkHandover(newNetwork: Network) {        if (_vpnState.value != VpnState.CONNECTED && _vpnState.value != VpnState.CONNECTING) return
        serviceScope.launch {
            try {
                if (settingsRepository.isKillSwitchEnabled) {
                    AppLogger.i(TAG, "Kill Switch: переключение туннеля на новую сеть без разрыва интерфейса (Zero Leak)...")
                }

                xrayController?.stopXray()
                delay(200)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    try {
                        setUnderlyingNetworks(arrayOf(newNetwork))
                    } catch (_: Throwable) {}
                }

                val pfd = vpnInterface
                val server = _activeServer.value ?: vlessKeyRepository.getVlessServers().firstOrNull()
                if (server != null && pfd != null && vpnInterface === pfd && _vpnState.value == VpnState.CONNECTED) {
                    val (started, errorMsg) = xrayController?.startXray(server, pfd.fd, currentTunMtu()) ?: Pair(false, "Unknown")
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

                stopVpn()
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

                xrayController?.stopXray()
                delay(200)

                val reconnectPlan = settingsRepository.selectedServerPlan
                val reconnectHasSubscription =
                    com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()
                val isFrancePlan = reconnectPlan == SettingsRepository.PLAN_PREMIUM_FRANCE && reconnectHasSubscription
                val isBelarusPlan = reconnectPlan == SettingsRepository.PLAN_PREMIUM_BELARUS && reconnectHasSubscription
                val isPaidPlan = settingsRepository.isPaidPlan(reconnectPlan) && reconnectHasSubscription

                val newServer = if (isFrancePlan) {
                    com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.ensureFranceVlessKey()
                    val activeKey = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.getActiveVlessKey()
                    val parsedServer = if (!activeKey.isNullOrBlank()) {
                        com.naua_security_mirage.app.data.model.VlessServer.fromUri(activeKey, "Франция (Платный)")
                    } else null

                    val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
                    val clientUuid = manager.getActiveClientUuid()
                    val france = if (!com.naua_security_mirage.app.data.supabase.FranceAccessPolicy
                            .mayBuildPaidTunnel(clientUuid)
                    ) {
                        null
                    } else {
                        applyFingerprintPreference(parsedServer ?: run {
                            val clientFlow = manager.getActiveClientFlow(clientUuid)
                            com.naua_security_mirage.app.data.supabase.SupabaseConfig.getFranceServer(clientUuid, clientFlow)
                        })
                    }
                    france?.let {
                        AppLogger.i(
                            TAG,
                            "Выбран платный узел для переподключения: ${it.tag} " +
                                    "(${it.address}:${it.port}), flow: '${it.flow}', " +
                                    "uuid: ${redactUuid(it.uuid)}"
                        )
                    }
                    france
                } else if (isBelarusPlan) {
                    resolveBelarusServer()
                } else {
                    val servers = vlessKeyRepository.getVlessServers()
                    val measured = pingRepository.measureAllPings(servers)
                    val best = pingRepository.selectBestServer(measured)
                    AppLogger.i(TAG, "Выбран оптимальный бесплатный узел для переподключения: ${best.tag}")
                    best
                }

                val pfd = vpnInterface
                val reconnectTarget = newServer
                if (pfd != null && reconnectTarget == null) {
                    AppLogger.e(TAG, "Платный узел недоступен для переподключения")
                    AppLogger.onUserMessage(getString(R.string.paid_unavailable_later))
                    _vpnState.value = VpnState.DISCONNECTED
                    stopVpn()
                    return@launch
                }
                if (pfd != null && reconnectTarget != null) {
                    val onPaidNode = isPaidPlan
                    val tunnelAttempt = withTimeoutOrNull(if (onPaidNode) 90_000L else 60_000L) {
                        openWorkingTunnel(reconnectTarget, onPaidNode, pfd)
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
        private const val PAID_TAG_PREFIX = "Франция"
        private const val PRIVATE_DNS_WARNING_COOLDOWN_MS = 30 * 60 * 1000L

         
        private const val CORE_SETTLE_MS = 1_500L

         
        private const val SOCKS_PORT = 10808

        private val AI_BLOCKED_COUNTRY_CODES = setOf(
            "RU", "BY", "KZ", "UZ", "TM", "KG", "TJ", "CN", "IR", "CU", "VE", "KP"
        )

        private val AI_APP_PACKAGES = listOf(
            "com.google.android.apps.bard",
            "com.google.android.googlequicksearchbox"
        )



        private const val TUN_MTU_WIFI = 1360
        private const val TUN_MTU_CELLULAR = 1280
        private const val WEAK_LINK_KBPS = 5000
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val WATCHDOG_PROBE_MS = 6000
        private const val WATCHDOG_MAX_FAILURES = 3
        private const val MAX_DROP_RECOVERIES = 3
        private const val DROP_RECOVERY_BACKOFF_MS = 5_000L
        private const val MAX_DROP_RECOVERY_BACKOFF_MS = 30_000L



        private const val FRANCE_KEY_BUDGET_MS = 2_500L
    private const val AUTO_DNS_TTL_MS = 10 * 60 * 1000L
    private const val AUTO_DNS_RETRY_MS = 3 * 60 * 1000L

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
