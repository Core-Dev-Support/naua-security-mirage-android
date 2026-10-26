package com.naua_security_mirage.app.vpn

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.util.AppLogger

class MirageNotifyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val enabled = SettingsRepository(this).isPaymentNotificationEnabled
        SettingsRepository(this).isPaymentNotificationEnabled = !enabled
        AppLogger.i(
            TAG,
            "Плитка шторки переключила уведомления об оплате: ${!enabled}"
        )
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (SettingsRepository(this).isPaymentNotificationEnabled) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "NotifyTile"

        fun requestUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestListeningState(
                        context,
                        ComponentName(context, MirageNotifyTileService::class.java)
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }
}