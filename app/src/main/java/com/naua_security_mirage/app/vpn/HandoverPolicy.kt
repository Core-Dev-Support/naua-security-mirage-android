package com.naua_security_mirage.app.vpn

object HandoverPolicy {

    fun shouldRestartTunnel(
        hadPreviousNetwork: Boolean,
        sameNetworkObject: Boolean,
        previousStillUsable: Boolean,
        tunnelConnected: Boolean,
        upgradeTransport: Boolean = false
    ): Boolean {
        if (!tunnelConnected) return false
        if (!hadPreviousNetwork) return false
        if (sameNetworkObject) return false

        // Перезапуск требуется, если прежняя сеть потеряна/непригодна ИЛИ произошёл апгрейд (4G -> Wi-Fi/Ethernet)
        return !previousStillUsable || upgradeTransport
    }
}
