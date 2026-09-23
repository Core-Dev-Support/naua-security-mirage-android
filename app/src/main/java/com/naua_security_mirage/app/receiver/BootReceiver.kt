package com.naua_security_mirage.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.vpn.MirageVpnService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        AppLogger.i(TAG, "BootReceiver получил намерение с действием: $action")

        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )

        if (action !in validActions) return

        val settingsRepository = SettingsRepository(context)
        if (!settingsRepository.isAutoStartOnBootEnabled) {
            AppLogger.i(TAG, "Автозапуск при старте отключен в настройках.")
            return
        }

        // Проверяем, дано ли системное разрешение на запуск VPN
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            AppLogger.w(TAG, "Невозможно запустить VPN автоматически: требуется подтверждение пользователя в приложении.")
            return
        }

        AppLogger.i(TAG, "Автозапуск VPN активирован. Запуск службы MirageVpnService...")
        try {
            MirageVpnService.start(context)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Ошибка при автозапуске MirageVpnService: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
