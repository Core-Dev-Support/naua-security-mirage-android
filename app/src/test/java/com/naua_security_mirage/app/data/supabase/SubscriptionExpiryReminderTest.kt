package com.naua_security_mirage.app.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionExpiryReminderTest {

    @Test
    fun silentWhileFarAway() {
        assertNull(SubscriptionPolicy.expiryReminderStep(365))
        assertNull(SubscriptionPolicy.expiryReminderStep(49))
        assertNull(SubscriptionPolicy.expiryReminderStep(4))
    }

    @Test
    fun firesAtThreeDays() {
        assertEquals(3, SubscriptionPolicy.expiryReminderStep(3))
        assertEquals(3, SubscriptionPolicy.expiryReminderStep(2))
    }

    @Test
    fun firesAtOneDay() {
        assertEquals(1, SubscriptionPolicy.expiryReminderStep(1))
    }

    @Test
    fun silentWhenExpired() {
        assertNull(SubscriptionPolicy.expiryReminderStep(0))
        assertNull(SubscriptionPolicy.expiryReminderStep(-1))
    }
}
