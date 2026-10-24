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
import com.naua_security_mirage.app.data.config.AppConfigRepository
import com.naua_security_mirage.app.data.config.PaidNode
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
import kotlinx.coroutines.coroutineScope
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
    private val pingSamples = ArrayDeque<Long>()
    @Volatile
    private var entryProbePingMs = -1L
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

     
    private var establishedTunMtu: Int = 0

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
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

        // Пул соединений мог сохранить сокеты от прошлого туннеля: они уже
        // мертвы, и первый же запрос к серверу подвиснет до таймаута.
        com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.resetHttpConnections()

        val previousConnectionJob = connectionJob
        connectionJob = serviceScope.launch {

            previousConnectionJob?.cancelAndJoin()
            if (_vpnState.value != VpnState.CONNECTING) return@launch
            captureUnderlyingNetwork()
            val isCellular = isCellularNetwork()
            AppLogger.i(TAG, "Тип сети на старте: ${describeUnderlyingNetwork()}")
            try {
                val selectedPlan = settingsRepository.selectedServerPlan
                val hasSubscription =
                    com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()
                // The catalogue is server-driven: a paid plan is whatever node id
                // app_config returned. France and Belarus keep their dedicated
                // key paths; every other kind is a generic VLESS node.
                val paidNode = if (hasSubscription) AppConfigRepository.node(selectedPlan) else null
                val isPaidPlan = paidNode != null
                val isBelarusPlan = paidNode?.kind == PaidNode.Kind.BELARUS

                var premeasuredFree: List<VlessServer> = emptyList()
                var freeLoadedCount = 0
                var freeAnyReachable = false
                var freePanelReachable = false
                val bestServer = if (paidNode != null) {
                    resolvePaidNode(paidNode)
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

                    val best = pickFreeServer(measuredServers)
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

                pickDnsAutomatically()
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


                if (tunnelAttempt?.first == null && onPaidNode) {
                    if (isBelarusPlan) {
                        // Кэшированный ключ мог устареть после ротации на панели.
                        // После неудачи один раз принудительно перечитываем его.
                        NodeEventStore.record(
                            this@MirageVpnService,
                            NodeEventStore.NODE_BELARUS,
                            NodeEventStore.STATE_DOWN,
                            "tunnel"
                        )
                    }
                    val retryServer = paidNode?.let { resolvePaidNode(it, force = true) }
                    if (retryServer != null) {
                        AppLogger.w(TAG, "Повтор платного подключения с ключом, полученным после обновления панели")
                        tunnelAttempt = withTimeoutOrNull(tunnelBudgetMs) {
                            openWorkingTunnel(retryServer, true, pfd, emptyList())
                        }
                    }
                }
                if (tunnelAttempt == null) {
                    if (isBelarusPlan) {
                        NodeEventStore.record(
                            this@MirageVpnService,
                            NodeEventStore.NODE_BELARUS,
                            NodeEventStore.STATE_DOWN,
                            "tunnel"
                        )
                    }
                    AppLogger.e(TAG, "Превышен лимит времени проверки VPN-узлов — VPN отключается.")
                    stopVpn(cancelConnectionJob = false)
                    return@launch
                }
                val (workingServer, _) = tunnelAttempt
                if (workingServer == null) {
                    if (isBelarusPlan) {
                        NodeEventStore.record(
                            this@MirageVpnService,
                            NodeEventStore.NODE_BELARUS,
                            NodeEventStore.STATE_DOWN,
                            "tunnel"
                        )
                    }
                    if (LockdownInspector.isOurAppLocked(this@MirageVpnService)) {
                        AppLogger.e(TAG, "Подключение невозможно: включена системная блокировка сети без VPN")
                        AppLogger.onUserMessage(getString(R.string.vpn_error_lockdown))
                    } else if (isPaidPlan) {
                        AppLogger.e(TAG, "Платный узел не подтвердил передачу трафика — VPN отключается.")
                        AppLogger.onUserMessage(getString(R.string.paid_unavailable_retry))
                    } else {
                        AppLogger.e(TAG, "Ни один узел не подтвердил передачу трафика — VPN отключается.")
                        val reason = when {
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

                if (isBelarusPlan) {
                    NodeEventStore.record(
                        this@MirageVpnService,
                        NodeEventStore.NODE_BELARUS,
                        NodeEventStore.STATE_UP,
                        null
                    )
                }

                _activeServer.value = activeServer
                _sessionSeconds.value = 0L
                pingSamples.clear()
                val seedPing = if (activeServer.pingMs in 1..9998) {
                    activeServer.pingMs
                } else {
                    entryProbePingMs
                }
                if (seedPing in 1..9998) {
                    _activePing.value = seedPing
                }
                _vpnState.value = VpnState.CONNECTED
                AppLogger.i(TAG, "VPN State changed to CONNECTED. Маршрутизация защищенного трафика через ${activeServer.tag} (Endpoint: Зашифрован)")
                notificationManager.updateConnected(
                    pingMs = _activePing.value,
                    durationStr = "00:00",
                    downBps = 0L,
                    upBps = 0L,
                    isSpeedEnabled = (settingsRepository.speedIntervalSeconds > 0)
                )

                serviceScope.launch(Dispatchers.IO) { checkPrivateDnsCompatibility(pfd) }

                if (seedPing in 1..9998) {
                    AppLogger.i(TAG, "Стартовый пинг сессии: ${seedPing} мс (без ожидания замера)")
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

    /**
     * Resolves any node listed in `app_config.paid_nodes`.
     *
     * France and Belarus have dedicated key paths because their providers
     * differ. Any other node is generic VLESS: its key is issued by the edge
     * function named in the config, so adding a node is a database change.
     */
    private suspend fun resolvePaidNode(node: PaidNode, force: Boolean = false): VlessServer? {
        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
        if (!manager.hasActiveSubscription()) return null
        return when (node.kind) {
            PaidNode.Kind.FRANCE -> resolveFranceServer(node.label())
            PaidNode.Kind.BELARUS -> {
                val refresh = serviceScope.async(Dispatchers.IO) {
                    manager.ensureBelarusVlessKey(force = force)
                }
                resolveBelarusServer(refresh, node.label())
            }
            PaidNode.Kind.VLESS -> resolveGenericServer(node, force)
        }
    }

    private suspend fun resolveFranceServer(label: String): VlessServer? {
        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
        val cachedKey = manager.getActiveVlessKey()
        val refresh = serviceScope.async(Dispatchers.IO) { manager.ensureFranceVlessKey() }
        val keyBudgetMs = if (cachedKey.isNullOrBlank()) FRANCE_KEY_BUDGET_MS else CACHED_KEY_BUDGET_MS
        val liveKey = withTimeoutOrNull(keyBudgetMs) { refresh.await() }
        if (liveKey.isNullOrBlank()) {
            AppLogger.w(
                TAG,
                "Панель не ответила за ${keyBudgetMs}мс, используется кэшированный ключ; " +
                        "обновление продолжится в фоне"
            )
        }
        val activeKey = manager.getActiveVlessKey()
        val parsedServer = if (!activeKey.isNullOrBlank()) {
            VlessServer.fromUri(activeKey, label)
        } else null

        val server = parsedServer ?: run {
            val clientUuid = manager.getActiveClientUuid()
            if (!com.naua_security_mirage.app.data.supabase.FranceAccessPolicy
                    .mayBuildPaidTunnel(clientUuid)
            ) {
                AppLogger.w(TAG, "У сервера нет ключа для этого аккаунта, платный узел пропущен")
                null
            } else {
                val clientFlow = manager.getActiveClientFlow(clientUuid)
                com.naua_security_mirage.app.data.supabase.SupabaseConfig
                    .getFranceServer(clientUuid, clientFlow)
            }
        }
        return server?.let { applyFingerprintPreference(it) }?.also {
            AppLogger.i(
                TAG,
                "Выбран платный узел: ${it.tag} (${it.address}:${it.port}), " +
                        "flow: '${it.flow}', uuid: ${redactUuid(it.uuid)}"
            )
        }
    }

    private suspend fun resolveGenericServer(node: PaidNode, force: Boolean): VlessServer? {
        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
        val cachedKey = manager.getNodeVlessKey(node.id)
        val refresh = serviceScope.async(Dispatchers.IO) {
            manager.ensureNodeVlessKey(node, force = force)
        }
        val budgetMs = if (cachedKey.isNullOrBlank()) FRANCE_KEY_BUDGET_MS else CACHED_KEY_BUDGET_MS
        val liveKey = withTimeoutOrNull(budgetMs) { refresh.await() }
        if (liveKey.isNullOrBlank() && !cachedKey.isNullOrBlank()) {
            AppLogger.w(
                TAG,
                "Панель не ответила за ${budgetMs}мс, используется кэшированный ключ узла ${node.id}; " +
                        "обновление продолжится в фоне"
            )
        }
        val key = liveKey?.takeIf { it.isNotBlank() }
            ?: manager.getNodeVlessKey(node.id)
            ?: cachedKey
        if (key.isNullOrBlank()) {
            AppLogger.w(TAG, "${node.configFunction} не выдал ключ, узел ${node.id} пропущен")
            return null
        }
        val server = VlessServer.fromUri(key, node.label())
        if (server == null) {
            AppLogger.w(TAG, "У узла ${node.id} некорректная VLESS-ссылка, узел пропущен")
            return null
        }
        if (!com.naua_security_mirage.app.data.supabase.FranceAccessPolicy
                .mayBuildPaidTunnel(server.uuid)
        ) {
            AppLogger.w(TAG, "У узла ${node.id} некорректный UUID, узел пропущен")
            return null
        }
        AppLogger.i(
            TAG,
            "Выбран платный узел: ${server.tag} (${server.address}:${server.port}), " +
                    "network=${server.network}, security=${server.security}, " +
                    "uuid: ${redactUuid(server.uuid)}"
        )
        return applyFingerprintPreference(server)
    }

    private suspend fun resolveBelarusServer(
        refresh: Deferred<String?>,
        label: String = com.naua_security_mirage.app.data.supabase.SupabaseConfig.BELARUS_LABEL
    ): VlessServer? {
        val manager = com.naua_security_mirage.app.data.supabase.SupabaseManager.instance
        val cachedKey = manager.getActiveBelarusVlessKey()
        val budgetMs = if (cachedKey.isNullOrBlank()) BELARUS_KEY_BUDGET_MS else CACHED_KEY_BUDGET_MS
        val liveKey = withTimeoutOrNull(budgetMs) { refresh.await() }
        if (liveKey.isNullOrBlank() && !cachedKey.isNullOrBlank()) {
            AppLogger.w(
                TAG,
                "Панель не ответила за ${budgetMs}мс, используется кэшированный ключ Беларуси; " +
                        "обновление продолжится в фоне"
            )
        }
        val key = liveKey?.takeIf { it.isNotBlank() } ?: cachedKey
        val server = if (!key.isNullOrBlank()) {
            com.naua_security_mirage.app.data.model.VlessServer.fromUri(
                key,
                label
            )
        } else {
            null
        }
        if (server == null) {
            AppLogger.w(TAG, "belarus-config не выдал ключ, узел Беларусь пропущен")
            NodeEventStore.record(
                this@MirageVpnService,
                NodeEventStore.NODE_BELARUS,
                NodeEventStore.STATE_DOWN,
                "no_key"
            )
            return null
        }
        if (!com.naua_security_mirage.app.data.supabase.FranceAccessPolicy
                .mayBuildPaidTunnel(server.uuid)
        ) {
            AppLogger.w(TAG, "У узла Беларусь некорректный UUID, платный узел пропущен")
            NodeEventStore.record(
                this@MirageVpnService,
                NodeEventStore.NODE_BELARUS,
                NodeEventStore.STATE_DOWN,
                "bad_key"
            )
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

            if (isPaidPlan) {

                serviceScope.launch { checkEndpointReachability(server) }
            }

            val result = attemptTunnel(server, pfd)
            coreStarted = coreStarted || result.coreStarted
            if (!isCurrentTunnel(pfd)) return null to coreStarted

            if (result.trafficFlows && true) {
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
                if (result.trafficFlows && true) {
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
                    if (result.trafficFlows && true) {
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
            if (result.trafficFlows && true) {
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

    private fun freeNodeKey(server: VlessServer): String =
        listOf(server.address, server.port.toString(), server.uuid).joinToString("|")

    private fun pickFreeServer(servers: List<VlessServer>): VlessServer {
        val preferred = settingsRepository.preferredFreeNodeKey
        if (!preferred.isNullOrBlank()) {
            val pinned = servers.firstOrNull { freeNodeKey(it) == preferred }
            if (pinned != null && pinned.pingMs in 1..9998) {
                AppLogger.i(TAG, "Бесплатный узел: используется выбранный ${pinned.tag} (${pinned.pingMs} мс)")
                return pinned
            }
            AppLogger.w(TAG, "Бесплатный узел: выбранный узел недоступен, берём лучший автоматически")
        }
        return pingRepository.selectBestServer(servers)
    }

    private class TunnelAttempt(val coreStarted: Boolean, val trafficFlows: Boolean)

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
        entryProbePingMs = -1L
        val reachability = pingRepository.measurePing(server, timeoutMs = 3000) { socket ->
            try {
                protect(socket)
            } catch (t: Throwable) {
                Log.w(TAG, "protect(socket) failed during reachability probe: ${t.message}")
            }
        }
        if (reachability in 1..9998) {
            entryProbePingMs = reachability
            AppLogger.i(TAG, "Платный вход ${server.address}:${server.port} доступен с устройства (${reachability} мс)")
            if (_vpnState.value == VpnState.CONNECTED && _activePing.value !in 1..9998) {
                _activePing.value = reachability
                AppLogger.i(TAG, "Пинг сессии взят из проверки входа: ${reachability} мс")
            }
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
        val hasPick = settingsRepo.autoDnsPickedId.isNotBlank()
        if (hasPick && age < AUTO_DNS_TTL_MS) return
        if (!hasPick && age < AUTO_DNS_RETRY_MS) return
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
        com.naua_security_mirage.app.util.LogHelper.trimXrayLogs(this)
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
            pingSamples.clear()
            while (isActive && _vpnState.value == VpnState.CONNECTED) {
                val intervalSec = settingsRepository.autoPingIntervalSeconds
                if (intervalSec <= 0) break

                val waitingForFirst = pingSamples.isEmpty()
                if (!waitingForFirst) {
                    delay(intervalSec * 1000L)
                    if (!isActive || _vpnState.value != VpnState.CONNECTED) break
                }

                val target = _activeServer.value ?: server
                val ping = pingRepository.measurePing(
                    target,
                    timeoutMs = if (waitingForFirst) FIRST_PING_TIMEOUT_MS else PING_TIMEOUT_MS,
                    samples = if (waitingForFirst) FIRST_PING_SAMPLES else PING_SAMPLES_PER_MEASUREMENT
                ) { socket ->
                    try {
                        protect(socket)
                    } catch (t: Throwable) {
                        Log.w(TAG, "protect(socket) failed: ${t.message}")
                    }
                }
                if (ping in 1..9998) {
                    if (pingSamples.size >= PING_HISTORY) pingSamples.removeFirst()
                    pingSamples.addLast(ping)
                    val ordered = pingSamples.sorted()
                    _activePing.value = ordered[ordered.size / 2]
                } else {
                    AppLogger.w(
                        TAG,
                        if (waitingForFirst) {
                            "Автопинг узла ${target.tag}: первая проба не ответила, повтор через ${FIRST_PING_RETRY_MS}мс"
                        } else {
                            "Автопинг узла ${target.tag} не ответил"
                        }
                    )
                    if (waitingForFirst) {
                        delay(FIRST_PING_RETRY_MS)
                        if (!isActive || _vpnState.value != VpnState.CONNECTED) break
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
        pingSamples.clear()
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
        serviceInstance = null
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
                    handleUnderlyingNetworkAvailable(network)
                }

                override fun onLost(network: Network) {
                    if (lastKnownNetwork != network) return

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

                    // Wi-Fi мог отключиться, а мобильная сеть уже доступна.
                    // Без этого туннель оставался бы привязанным к потерянной сети.
                    val replacement = pickBestUnderlyingNetwork()
                    if (replacement != null) {
                        lastKnownNetwork = replacement
                        applyUnderlyingNetwork(replacement)
                        if (_vpnState.value == VpnState.CONNECTED) {
                            handleNetworkHandover(replacement)
                        }
                    }
                }
            }
            cm.registerNetworkCallback(request, networkCallback!!)

            // Сразу выбираем лучшую доступную сеть: Wi-Fi/Ethernet важнее
            // мобильной. Иначе onAvailable для 4G мог привязать туннель к
            // мобильной сети, хотя Wi-Fi уже подключён.
            pickBestUnderlyingNetwork()?.let { network ->
                lastKnownNetwork = network
                applyUnderlyingNetwork(network)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun handleUnderlyingNetworkAvailable(network: Network) {
        val cm = connectivityManager ?: return
        val previous = lastKnownNetwork
        val previousCaps = previous?.let { cm.getNetworkCapabilities(it) }
        val newCaps = cm.getNetworkCapabilities(network)
        val previousUsable = previous?.let { isStillUsable(it) } ?: true

        // Не переключаемся с рабочего Wi-Fi на мобильную сеть только потому,
        // что Android сообщил о ней вторым onAvailable. Обратное направление
        // (4G -> Wi-Fi) разрешено: там ранг сети выше.
        if (previous != null && previous != network && previousUsable &&
            transportRank(newCaps) <= transportRank(previousCaps)
        ) {
            return
        }

        val switched = HandoverPolicy.shouldRestartTunnel(
            hadPreviousNetwork = previous != null,
            sameNetworkObject = previous == network,
            previousStillUsable = previousUsable,
            tunnelConnected = _vpnState.value == VpnState.CONNECTED
        )
        if (switched) {
            AppLogger.i(TAG, "Смена активной сети (Handover). Перезапуск туннеля...")
            handleNetworkHandover(network)
        }
        lastKnownNetwork = network
        applyUnderlyingNetwork(network)
    }

    private fun applyUnderlyingNetwork(network: Network) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                setUnderlyingNetworks(arrayOf(network))
            } catch (e: Throwable) {
                Log.w(TAG, "setUnderlyingNetworks failed: ${e.message}")
            }
        }
    }

    private fun transportRank(caps: NetworkCapabilities?): Int {
        if (caps == null) return 0
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 0
        }
    }

    private fun pickBestUnderlyingNetwork(): Network? {
        val cm = connectivityManager ?: return null
        return try {
            val candidates = (cm.allNetworks.toList() + listOfNotNull(cm.activeNetwork)).distinct()
            candidates
                .mapNotNull { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    ) {
                        return@mapNotNull null
                    }
                    network to transportRank(caps)
                }
                .maxByOrNull { it.second }
                ?.first
        } catch (_: Throwable) {
            null
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
                val reconnectNode =
                    if (reconnectHasSubscription) AppConfigRepository.node(reconnectPlan) else null
                val isPaidPlan = reconnectNode != null
                val isBelarusPlan = reconnectNode?.kind == PaidNode.Kind.BELARUS

                val newServer = if (reconnectNode != null) {
                    resolvePaidNode(reconnectNode)?.also {
                        AppLogger.i(
                            TAG,
                            "Выбран платный узел для переподключения: ${it.tag} " +
                                    "(${it.address}:${it.port}), uuid: ${redactUuid(it.uuid)}"
                        )
                    }
                } else {
                    val servers = vlessKeyRepository.getVlessServers()
                    val measured = pingRepository.measureAllPings(servers)
                    val best = pickFreeServer(measured)
                    AppLogger.i(TAG, "Выбран оптимальный бесплатный узел для переподключения: ${best.tag}")
                    best
                }

                val pfd = vpnInterface
                val reconnectTarget = newServer
                if (pfd != null && reconnectTarget == null) {
                    if (isBelarusPlan) {
                        NodeEventStore.record(
                            this@MirageVpnService,
                            NodeEventStore.NODE_BELARUS,
                            NodeEventStore.STATE_DOWN,
                            "no_key"
                        )
                    }
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
                        if (isBelarusPlan) {
                            NodeEventStore.record(
                                this@MirageVpnService,
                                NodeEventStore.NODE_BELARUS,
                                NodeEventStore.STATE_UP,
                                null
                            )
                        }
                        _tunnelHealthy.value = true
                        _activeServer.value = active
                        _vpnState.value = VpnState.CONNECTED
                        if (active.pingMs in 1..9998) {
                            _activePing.value = active.pingMs
                        }
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
                        if (isBelarusPlan) {
                            NodeEventStore.record(
                                this@MirageVpnService,
                                NodeEventStore.NODE_BELARUS,
                                NodeEventStore.STATE_DOWN,
                                "tunnel"
                            )
                        }
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
        private const val PRIVATE_DNS_WARNING_COOLDOWN_MS = 30 * 60 * 1000L

         
        private const val CORE_SETTLE_MS = 1_500L

         
        private const val SOCKS_PORT = 10808

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
    // Беларусь отвечает через панель H1Cloud: первый холодный запрос бывает
    // медленнее, поэтому даём больше времени, чем Франции.
    private const val BELARUS_KEY_BUDGET_MS = 8_000L
    private const val CACHED_KEY_BUDGET_MS = 1_200L
    private const val AUTO_DNS_TTL_MS = 10 * 60 * 1000L
    private const val AUTO_DNS_RETRY_MS = 3 * 60 * 1000L
    private const val PING_SAMPLES_PER_MEASUREMENT = 3
    private const val PING_HISTORY = 5
    private const val FIRST_PING_TIMEOUT_MS = 1_200
    private const val FIRST_PING_SAMPLES = 2
    private const val FIRST_PING_RETRY_MS = 1_000L
    private const val PING_TIMEOUT_MS = 2_000

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

        private var serviceInstance: MirageVpnService? = null

        fun protectSocket(socket: java.net.Socket): Boolean {
            val service = serviceInstance ?: return false
            return try {
                service.protect(socket)
            } catch (t: Throwable) {
                Log.w(TAG, "protect(socket) не удался: ${t.message}")
                false
            }
        }

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
