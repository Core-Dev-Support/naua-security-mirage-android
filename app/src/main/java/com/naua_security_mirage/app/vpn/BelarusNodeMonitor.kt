package com.naua_security_mirage.app.vpn

import android.content.Context
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.data.repository.PingRepository
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.data.supabase.SupabaseConfig
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Проверка платного узла Беларуси с устройства.
 *
 * Узел нельзя проверить через серверный node-status: он доступен не всем
 * сетям одинаково, поэтому именно клиент решает, работает ли он. Результат
 * каждого перехода пишется в [NodeEventStore] и попадает в диагностику.
 */
object BelarusNodeMonitor {

    private const val TAG = "BelarusNode"
    private const val PING_TIMEOUT_MS = 3_000
    const val TIMEOUT_PING = 9999L

    data class Probe(
        val server: VlessServer?,
        val pingMs: Long,
        val reason: String?
    ) {
        val reachable: Boolean
            get() = pingMs in 1..9998
    }

    private val pingRepository = PingRepository()

    suspend fun probe(context: Context, forceRefresh: Boolean = false): Probe {
        val manager = SupabaseManager.instance
        if (!manager.hasActiveSubscription()) {
            return Probe(null, TIMEOUT_PING, "no_subscription")
        }

        val key = runCatching { manager.ensureBelarusVlessKey(force = forceRefresh) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: manager.getActiveBelarusVlessKey()?.takeIf { it.isNotBlank() }
        if (key == null) {
            record(context, NodeEventStore.STATE_DOWN, "no_key")
            return Probe(null, TIMEOUT_PING, "no_key")
        }

        val server = VlessServer.fromUri(key, SupabaseConfig.BELARUS_LABEL)
        if (server == null) {
            record(context, NodeEventStore.STATE_DOWN, "bad_key")
            return Probe(null, TIMEOUT_PING, "bad_key")
        }

        val ping = try {
            pingRepository.measurePing(server, timeoutMs = PING_TIMEOUT_MS) { socket ->
                MirageVpnService.protectSocket(socket)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Проверка Беларуси не удалась: ${t.message}")
            TIMEOUT_PING
        }

        if (ping in 1..9998) {
            record(context, NodeEventStore.STATE_UP, null)
            return Probe(server, ping, null)
        }

        record(context, NodeEventStore.STATE_DOWN, "no_ping")
        return Probe(server, ping, "no_ping")
    }

    /** Проверяет Беларусь только если это выбранный (или последний платный) узел. */
    suspend fun checkTracked(context: Context) {
        val settings = SettingsRepository(context)
        val plan = if (settings.isPaidPlan()) settings.selectedServerPlan else settings.lastPaidPlan
        if (plan != SettingsRepository.PLAN_PREMIUM_BELARUS) return
        if (!SupabaseManager.instance.hasActiveSubscription()) return
        probe(context)
    }

    private suspend fun record(context: Context, state: String, reason: String?) {
        withContext(Dispatchers.IO) {
            NodeEventStore.record(context, NodeEventStore.NODE_BELARUS, state, reason)
        }
    }
}
