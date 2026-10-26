package com.naua_security_mirage.app.vpn

object HandoverPolicy {

    fun shouldRestartTunnel(
        hadPreviousNetwork: Boolean,
        sameNetworkObject: Boolean,
        previousStillUsable: Boolean,
        tunnelConnected: Boolean
    ): Boolean {
        if (!tunnelConnected) return false
        if (!hadPreviousNetwork) return false
        if (sameNetworkObject) return false

        return !previousStillUsable
    }
}
