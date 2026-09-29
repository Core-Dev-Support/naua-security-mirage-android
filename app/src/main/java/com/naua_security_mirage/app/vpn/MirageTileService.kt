package com.naua_security_mirage.app.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class MirageTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateCollectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(MirageVpnService.vpnState.value)

        stateCollectJob?.cancel()
        stateCollectJob = serviceScope.launch {
            MirageVpnService.vpnState.collect { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        stateCollectJob?.cancel()
        stateCollectJob = null
    }

    override fun onClick() {
        super.onClick()
        val currentState = MirageVpnService.vpnState.value
        when (currentState) {
            VpnState.CONNECTED, VpnState.CONNECTING -> {
                MirageVpnService.stop(this)
                updateTileState(VpnState.DISCONNECTING)
            }
            VpnState.DISCONNECTED, VpnState.DISCONNECTING -> {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_QUICK_CONNECT
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pendingIntent = PendingIntent.getActivity(
                            this,
                            0,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        startActivityAndCollapse(pendingIntent)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(intent)
                    }
                } else {
                    MirageVpnService.start(this)
                    updateTileState(VpnState.CONNECTING)
                }
            }
        }
    }

    private fun updateTileState(state: VpnState) {
        val tile = qsTile ?: return
        when (state) {
            VpnState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.quick_tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.tile_subtitle_connected)
                }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_stat)
            }
            VpnState.CONNECTING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.quick_tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.tile_subtitle_connecting)
                }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_stat)
            }
            VpnState.DISCONNECTING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.quick_tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.tile_subtitle_disconnecting)
                }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_stat)
            }
            VpnState.DISCONNECTED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.quick_tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.tile_subtitle_disconnected)
                }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_stat)
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        stateCollectJob?.cancel()
        serviceScope.cancel()
    }

    companion object {
        fun requestUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestListeningState(
                        context,
                        ComponentName(context, MirageTileService::class.java)
                    )
                } catch (_: Throwable) {}
            }
        }

        fun setTileEnabled(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val component = ComponentName(context, MirageTileService::class.java)
                    val newState = if (enabled) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    context.packageManager.setComponentEnabledSetting(
                        component,
                        newState,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Throwable) {}
            }
        }
    }
}
