package com.naua_security_mirage.app.data.supabase

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Single source of truth for subscription expiry checks.
 *
 * Supabase, 3X-UI and the local cache can all return slightly different ISO-8601
 * representations.  Always parse them as UTC and never treat a missing/invalid
 * expiry as an active paid entitlement.
 */
object SubscriptionPolicy {
    private val fractionPattern = Regex("\\.(\\d+)")
    private val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss"
    )

    fun parseExpiry(value: String?): Date? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val normalized = fractionPattern.replace(raw) { match ->
            "." + match.groupValues[1].take(3).padEnd(3, '0')
        }

        for (pattern in patterns) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val position = ParsePosition(0)
            val parsed = parser.parse(normalized, position)
            if (parsed != null && position.index == normalized.length) return parsed
        }
        return null
    }

    fun isActive(isActive: Boolean, paidUntil: String?, now: Date = Date()): Boolean {
        if (!isActive) return false
        val expiry = parseExpiry(paidUntil) ?: return false
        return expiry.after(now)
    }

    /**
     * A paid cache may be cleared only after Supabase answered successfully. A
     * 3X-UI outage must not turn a paid cache into a free plan.
     */
    fun shouldClearCache(
        hasActiveCache: Boolean,
        supabaseAuthoritative: Boolean,
        threeXUiAuthoritative: Boolean,
    ): Boolean {
        return supabaseAuthoritative && (threeXUiAuthoritative || !hasActiveCache)
    }

    fun formatUtcIso(expiry: Date): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(expiry)
    }
}
