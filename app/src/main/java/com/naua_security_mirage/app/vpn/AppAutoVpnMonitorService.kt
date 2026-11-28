package com.naua_security_mirage.app.vpn

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.ui.MainActivity
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Monitors foreground app changes via UsageStatsManager (no Restricted Settings gate)
 * to auto-connect VPN when a selected app opens and disconnect after leaving it.
 */
class AppAutoVpnMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var settingsRepository: SettingsRepository
    private var monitorJob: Job? = null

    @Volatile
    private var autoConnectedByWatch = false

    @Volatile
    private var activeWatchedPackage: String? = null

    private var lastForegroundPkg: String? = null
    private var leftAtElapsedMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        ensureNotificationChannel()
        val notification = buildMonitorNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                MONITOR_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(MONITOR_NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            MirageVpnService.vpnState.collect { state ->
                if (state == VpnState.DISCONNECTED) {
                    autoConnectedByWatch = false
                    activeWatchedPackage = null
                    leftAtElapsedMs = 0L
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!settingsRepository.isAutoVpnOnAppEnabled || !hasUsageStatsPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startMonitorLoop()
        return START_STICKY
    }

    private fun startMonitorLoop() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm == null) {
                stopSelf()
                return@launch
            }
            var lastQueryTime = System.currentTimeMillis() - 2_000L
            while (isActive) {
                if (!settingsRepository.isAutoVpnOnAppEnabled || !hasUsageStatsPermission(this@AppAutoVpnMonitorService)) {
                    stopSelf()
                    break
                }
                val now = System.currentTimeMillis()
                val queryStart = (lastQueryTime - 200L).coerceAtLeast(now - 10_000L)
                runCatching {
                    val events = usm.queryEvents(queryStart, now)
                    val event = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        val isForegroundEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                        } else {
                            @Suppress("DEPRECATION")
                            event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                        }
                        if (isForegroundEvent) {
                            val pkg = event.packageName?.takeIf { it.isNotBlank() } ?: continue
                            if (!isIgnoredOverlayPackage(pkg)) {
                                lastForegroundPkg = pkg
                            }
                        }
                    }
                }
                lastQueryTime = now

                val currentPkg = lastForegroundPkg
                if (currentPkg != null) {
                    val watched = settingsRepository.autoVpnAppPackages
                    if (watched.contains(currentPkg)) {
                        leftAtElapsedMs = 0L
                        activeWatchedPackage = currentPkg
                        if (MirageVpnService.vpnState.value == VpnState.DISCONNECTED &&
                            VpnService.prepare(this@AppAutoVpnMonitorService) == null
                        ) {
                            autoConnectedByWatch = true
                            AppLogger.i(TAG, "Открыто приложение $currentPkg — автоподключение VPN")
                            runCatching { MirageVpnService.start(this@AppAutoVpnMonitorService) }
                                .onFailure { AppLogger.w(TAG, "Не удалось автоподключить VPN: ${it.message}") }
                        }
                    } else if (autoConnectedByWatch) {
                        val elapsed = SystemClock.elapsedRealtime()
                        if (leftAtElapsedMs == 0L) {
                            leftAtElapsedMs = elapsed
                        } else if (elapsed - leftAtElapsedMs >= DISCONNECT_DEBOUNCE_MS) {
                            AppLogger.i(TAG, "Выход из ${activeWatchedPackage ?: "приложения"} ($currentPkg) — автоотключение VPN")
                            autoConnectedByWatch = false
                            activeWatchedPackage = null
                            leftAtElapsedMs = 0L
                            if (MirageVpnService.vpnState.value != VpnState.DISCONNECTED) {
                                runCatching { MirageVpnService.stop(this@AppAutoVpnMonitorService) }
                                    .onFailure { AppLogger.w(TAG, "Не удалось автоотключить VPN: ${it.message}") }
                            }
                        }
                    }
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun isIgnoredOverlayPackage(pkg: String): Boolean {
        if (pkg == packageName || pkg in IGNORED_SYSTEM_PACKAGES) return true
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.auto_vpn_service_label),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildMonitorNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_stat)
            .setContentTitle(getString(R.string.auto_vpn_service_label))
            .setContentText(getString(R.string.auto_vpn_monitor_notif_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AutoVpn"
        private const val CHANNEL_ID = "mirage_auto_vpn_channel"
        private const val MONITOR_NOTIFICATION_ID = 4209
        private const val POLL_INTERVAL_MS = 1_000L
        private const val DISCONNECT_DEBOUNCE_MS = 5_000L

        private val IGNORED_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.lbe.security.miui",
            "com.miui.securitycenter",
            "com.android.intentresolver",
            "com.google.android.gms",
            "com.google.android.providers.media.module",
            "com.android.providers.media.module"
        )

        fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun syncState(context: Context) {
            val settings = SettingsRepository(context)
            val shouldRun = settings.isAutoVpnOnAppEnabled && hasUsageStatsPermission(context)
            val intent = Intent(context, AppAutoVpnMonitorService::class.java)
            if (shouldRun) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }
}
