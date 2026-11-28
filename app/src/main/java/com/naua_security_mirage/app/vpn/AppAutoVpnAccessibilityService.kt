package com.naua_security_mirage.app.vpn

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches foreground window changes to auto-connect VPN when a selected app
 * opens and auto-disconnect when the user leaves all selected apps.
 */
class AppAutoVpnAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var settingsRepository: SettingsRepository
    private var pendingDisconnectJob: Job? = null

    @Volatile
    private var autoConnectedByWatch = false

    @Volatile
    private var activeWatchedPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = SettingsRepository(this)
        serviceScope.launch {
            MirageVpnService.vpnState.collect { state ->
                if (state == VpnState.DISCONNECTED) {
                    autoConnectedByWatch = false
                    activeWatchedPackage = null
                }
            }
        }
        AppLogger.i(TAG, "Служба авто-VPN подключена")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (!::settingsRepository.isInitialized || !settingsRepository.isAutoVpnOnAppEnabled) return
        if (isIgnoredOverlayPackage(pkg)) return

        val watched = settingsRepository.autoVpnAppPackages
        if (watched.contains(pkg)) {
            pendingDisconnectJob?.cancel()
            pendingDisconnectJob = null
            activeWatchedPackage = pkg

            if (MirageVpnService.vpnState.value == VpnState.DISCONNECTED &&
                VpnService.prepare(this) == null
            ) {
                autoConnectedByWatch = true
                AppLogger.i(TAG, "Открыто приложение $pkg — автоподключение VPN")
                runCatching { MirageVpnService.start(this) }
                    .onFailure { AppLogger.w(TAG, "Не удалось автоподключить VPN: ${it.message}") }
            }
        } else if (autoConnectedByWatch && pendingDisconnectJob?.isActive != true) {
            pendingDisconnectJob = serviceScope.launch {
                delay(DISCONNECT_DEBOUNCE_MS)
                if (autoConnectedByWatch && MirageVpnService.vpnState.value != VpnState.DISCONNECTED) {
                    AppLogger.i(TAG, "Выход из ${activeWatchedPackage ?: "приложения"} ($pkg) — автоотключение VPN")
                    autoConnectedByWatch = false
                    activeWatchedPackage = null
                    runCatching { MirageVpnService.stop(this@AppAutoVpnAccessibilityService) }
                        .onFailure { AppLogger.w(TAG, "Не удалось автоотключить VPN: ${it.message}") }
                }
            }
        }
    }

    private fun isIgnoredOverlayPackage(pkg: String): Boolean {
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") {
            return true
        }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() {
        pendingDisconnectJob?.cancel()
    }

    override fun onDestroy() {
        pendingDisconnectJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AutoVpn"
        private const val DISCONNECT_DEBOUNCE_MS = 2500L

        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val target = ComponentName(context, AppAutoVpnAccessibilityService::class.java)
            return enabledServices.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == target
            }
        }
    }
}
