package com.naua_security_mirage.app.ui.view

object CountryFlagHelper {
    fun countryCodeToEmoji(code: String?): String {
        if (code.isNullOrBlank()) return "🌐"
        val clean = code.trim()
        if (clean.codePoints().anyMatch { Character.isSurrogate(it.toChar()) || it > 0x1F000 }) {
            return clean
        }
        val upper = clean.uppercase()
        if (upper.length == 2 && upper.all { it in 'A'..'Z' }) {
            val firstChar = Character.toChars(0x1F1E6 + (upper[0] - 'A'))
            val secondChar = Character.toChars(0x1F1E6 + (upper[1] - 'A'))
            return String(firstChar) + String(secondChar)
        }
        return when (clean.lowercase()) {
            "switzerland", "swiss", "ch" -> "🇨🇭"
            "belarus", "by" -> "🇧🇾"
            "france", "fr" -> "🇫🇷"
            "turkey", "tr" -> "🇹🇷"
            "israel", "il" -> "🇮🇱"
            "germany", "de" -> "🇩🇪"
            "finland", "fi" -> "🇫🇮"
            "netherlands", "nl" -> "🇳🇱"
            "united_states", "usa", "us" -> "🇺🇸"
            "united_kingdom", "uk", "gb" -> "🇬🇧"
            "russia", "ru" -> "🇷🇺"
            "poland", "pl" -> "🇵🇱"
            "sweden", "se" -> "🇸🇪"
            else -> "🌐"
        }
    }
}
