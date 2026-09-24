package com.naua_security_mirage.app.data.supabase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class SubscriptionPolicyTest {
    private val now = Date(1_789_000_000_000L)

    @Test
    fun parsesUtcWithZ() {
        assertNotNull(SubscriptionPolicy.parseExpiry("2099-01-01T00:00:00.000Z"))
        assertTrue(SubscriptionPolicy.isActive(true, "2099-01-01T00:00:00.000Z", now))
    }

    @Test
    fun parsesOffsetAndLegacyValues() {
        assertTrue(SubscriptionPolicy.isActive(true, "2099-01-01T03:00:00+03:00", now))
        assertTrue(SubscriptionPolicy.isActive(true, "2099-01-01T00:00:00", now))
        assertTrue(SubscriptionPolicy.isActive(true, "2099-01-01T00:00:00.123456+00:00", now))
    }

    @Test
    fun rejectsExpiredMissingAndMalformedDates() {
        assertFalse(SubscriptionPolicy.isActive(true, "2000-01-01T00:00:00.000Z", now))
        assertFalse(SubscriptionPolicy.isActive(true, null, now))
        assertFalse(SubscriptionPolicy.isActive(true, "not-a-date", now))
    }

    @Test
    fun inactiveFlagAlwaysWins() {
        assertFalse(SubscriptionPolicy.isActive(false, "2099-01-01T00:00:00.000Z", now))
    }

    @Test
    fun uncertainRefreshCannotClearPaidCache() {
        assertFalse(
            SubscriptionPolicy.shouldClearCache(
                hasActiveCache = true,
                supabaseAuthoritative = false,
                threeXUiAuthoritative = false
            )
        )
        assertFalse(
            SubscriptionPolicy.shouldClearCache(
                hasActiveCache = true,
                supabaseAuthoritative = true,
                threeXUiAuthoritative = false
            )
        )
    }

    @Test
    fun authoritativeEmptySourcesClearOnlyWhenThereIsNoCache() {
        assertTrue(
            SubscriptionPolicy.shouldClearCache(
                hasActiveCache = false,
                supabaseAuthoritative = true,
                threeXUiAuthoritative = false
            )
        )
        assertTrue(
            SubscriptionPolicy.shouldClearCache(
                hasActiveCache = true,
                supabaseAuthoritative = true,
                threeXUiAuthoritative = true
            )
        )
    }
}
