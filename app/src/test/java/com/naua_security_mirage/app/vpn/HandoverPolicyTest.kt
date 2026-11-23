package com.naua_security_mirage.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoverPolicyTest {

    private fun restart(
        hadPrevious: Boolean = true,
        sameObject: Boolean = false,
        previousUsable: Boolean = true,
        connected: Boolean = true,
        upgradeTransport: Boolean = false
    ) = HandoverPolicy.shouldRestartTunnel(
        hadPreviousNetwork = hadPrevious,
        sameNetworkObject = sameObject,
        previousStillUsable = previousUsable,
        tunnelConnected = connected,
        upgradeTransport = upgradeTransport
    )

    @Test
    fun transportUpgradeRestartsTheTunnelEvenIfOldNetworkUsable() {
        assertTrue(restart(hadPrevious = true, sameObject = false, previousUsable = true, upgradeTransport = true))
    }

    @Test
    fun reAnnouncementOfTheSameLinkDoesNotRestartTheTunnel() {
        assertFalse(restart(hadPrevious = true, sameObject = false, previousUsable = true))
    }

    @Test
    fun aGenuineSwitchRestartsTheTunnel() {
        assertTrue(restart(hadPrevious = true, sameObject = false, previousUsable = false))
    }

    @Test
    fun theSameNetworkObjectNeverRestartsTheTunnel() {
        assertFalse(restart(sameObject = true, previousUsable = false))
    }

    @Test
    fun nothingIsRestartedWhenThereIsNoTunnel() {
        assertFalse(restart(previousUsable = false, connected = false))
    }

    @Test
    fun theFirstNetworkAfterStartupDoesNotRestartAnything() {
        assertFalse(restart(hadPrevious = false, previousUsable = false))
    }
}
