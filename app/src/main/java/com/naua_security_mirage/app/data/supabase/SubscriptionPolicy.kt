package com.naua_security_mirage.app.data.supabase

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SubscriptionPolicy {
    private val fractionPattern = Regex("\\.(\\d+)")
    private val patterns = listOf(

        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss"
    )

    fun parseExpiry(value: String?): Date? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        var normalized = fractionPattern.replace(raw) { match ->
            "." + match.groupValues[1].take(3).padEnd(3, '0')
        }
        if (normalized.endsWith("Z")) {
            normalized = normalized.dropLast(1) + "+0000"
        }
        normalized = normalized.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")

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
