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

            previousConnectionJob?.cancelAndJoin()
            if (_vpnState.value != VpnState.CONNECTING) return@launch
            try {
                val isFrancePlan = settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE &&
                        com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()

                var premeasuredFree: List<VlessServer> = emptyList()
                var franceRefresh: Deferred<String?>? = null
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
                } else {

                    val servers = vlessKeyRepository.getVlessServers()
                    AppLogger.i(TAG, "Loaded ${servers.size} servers. Measuring latency...")
                    Log.d(TAG, "Loaded ${servers.size} servers. Measuring latency...")

                    val measuredServers = pingRepository.measureAllPings(servers)
                    measuredServers.forEach { s ->
                        val pingText = if (s.pingMs in 1..9998) "${s.pingMs} ms" else "таймаут"
                        AppLogger.d(TAG, "Узел ${s.tag} -> задержка: $pingText")
                    }

                    val best = pingRepository.selectBestServer(measuredServers)
                    val bestPingText = if (best.pingMs in 1..9998) "${best.pingMs} ms" else "доступен"
                    AppLogger.i(TAG, "Выбран оптимальный узел: ${best.tag} (задержка: $bestPingText)")

                    premeasuredFree = measuredServers
                    best
                }


                if (isFrancePlan && bestServer == null) {
                    AppLogger.e(TAG, "Платный узел недоступен, соединение не устанавливается")
                    AppLogger.onUserMessage("Платный узел недоступен. Попробуйте позже.")
                    _vpnState.value = VpnState.DISCONNECTED
                    return@launch
                }
                val selectedServer = bestServer!!


                captureUnderlyingNetwork()

                establishedTunMtu = currentTunMtu()
                val builder = Builder()
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(establishedTunMtu)
                    .setSession("Mirage VPN")

                AppLogger.i(TAG, "Физическая сеть: ${describeUnderlyingNetwork()}, MTU туннеля: $establishedTunMtu")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

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

                val onPaidNode = bestServer != null
                val tunnelBudgetMs = if (isFrancePlan && onPaidNode) 90_000L else 60_000L
                var tunnelAttempt = withTimeoutOrNull(tunnelBudgetMs) {
                    openWorkingTunnel(selectedServer, isFrancePlan && onPaidNode, pfd, premeasuredFree)
                }


                if (tunnelAttempt?.first == null && isFrancePlan && onPaidNode && franceRefresh != null && !franceRefresh.isCompleted) {
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
                    if (isFrancePlan) {
                        AppLogger.e(TAG, "Платный узел не подтвердил передачу трафика — VPN отключается.")
                        AppLogger.onUserMessage("Платный узел недоступен. Попробуйте ещё раз.")
                    } else {
                        AppLogger.e(TAG, "Ни один узел не подтвердил передачу трафика — VPN отключается.")
                        AppLogger.onUserMessage("Узлы не отвечают. Попробуйте ещё раз.")
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

                startSessionTimer()

                startPeriodicPing(activeServer)

                startSpeedMonitoring()

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Error starting VPN: ${e.message}", e)
                stopVpn(cancelConnectionJob = false)
            }
        }
    }

    private suspend fun openWorkingTunnel(
        preferred: VlessServer,
        isPaidPlan: Boolean,
        pfd: ParcelFileDescriptor,
        premeasuredFree: List<VlessServer> = emptyList()
    ): Pair<VlessServer?, Boolean> {
        val candidates = mutableListOf(preferred)


        if (isPaidPlan) {
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

            if (result.trafficFlows) {
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
                if (result.trafficFlows) {
                    AppLogger.i(TAG, "Автоматическое переключение на рабочий узел: ${server.tag}")
                    return server to true
                }
                AppLogger.w(TAG, "Узел ${server.tag}: трафик не подтверждён, пробуем следующий узел...")
            }
        }
        return null to coreStarted
    }

    private suspend fun checkPrivateDnsCompatibility(pfd: ParcelFileDescriptor) {
        delay(1500)
        if (!isCurrentTunnel(pfd)) return

        val state = PrivateDnsInspector.read(this)
        AppLogger.i(TAG, "Частный DNS: ${PrivateDnsInspector.describe(state)}")

        if (state.mode != PrivateDnsInspector.Mode.STRICT) return

        val specifier = state.specifier?.trim().orEmpty()
        if (specifier.isEmpty()) {
            AppLogger.w(TAG, "Частный DNS строгий, но провайдер не указан — проверка пропущена")
            return
        }

        val reachable = xrayController?.probeTcpViaSocks(specifier, 853, 4000) == true
        if (reachable) {
            AppLogger.i(TAG, "Частный DNS ($specifier:853) отвечает через туннель — конфликта нет")
            return
        }

        AppLogger.w(
            TAG,
            "Частный DNS ($specifier:853) не отвечает через туннель, хотя трафик туннеля только что проверен"
        )
        val now = System.currentTimeMillis()
        if (now - lastPrivateDnsWarningAt < PRIVATE_DNS_WARNING_COOLDOWN_MS) return
        lastPrivateDnsWarningAt = now
        AppLogger.onUserMessage(getString(R.string.private_dns_conflict))
    }

    private suspend fun loadFreeCandidates(): List<VlessServer> {
        return try {
            val freeServers = vlessKeyRepository.getVlessServers()
            val measured = pingRepository.measureAllPings(freeServers)
            val ordered = measured.sortedBy { if (it.pingMs in 1..9998) it.pingMs else Long.MAX_VALUE }
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

    private suspend fun attemptTunnel(
        server: VlessServer,
        pfd: ParcelFileDescriptor
    ): TunnelAttempt {
        if (!isCurrentTunnel(pfd)) {
            return TunnelAttempt(coreStarted = false, trafficFlows = false)
        }
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
        if (controller.verifyDataPlane(5000)) return true
        delay(400)
        return controller.verifyDataPlane(3500)
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

                    delay(15_000)
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



    private fun currentTunMtu(): Int {
        val frozen = establishedTunMtu
        if (frozen > 0) return frozen
        return if (isCellularNetwork()) TUN_MTU_CELLULAR else TUN_MTU_WIFI
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

                val isFrancePlan = settingsRepository.selectedServerPlan == SettingsRepository.PLAN_PREMIUM_FRANCE &&
                        com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.hasActiveSubscription()

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
                    AppLogger.onUserMessage("Платный узел недоступен. Попробуйте позже.")
                    _vpnState.value = VpnState.DISCONNECTED
                    stopVpn()
                    return@launch
                }
                if (pfd != null && reconnectTarget != null) {
                    val onPaidNode = isFrancePlan && newServer != null
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



        private const val TUN_MTU_WIFI = 1360
        private const val TUN_MTU_CELLULAR = 1280



        private const val FRANCE_KEY_BUDGET_MS = 2_500L

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
