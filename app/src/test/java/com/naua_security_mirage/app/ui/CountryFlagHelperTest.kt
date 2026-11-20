package com.naua_security_mirage.app.ui

import com.naua_security_mirage.app.ui.view.CountryFlagHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class CountryFlagHelperTest {
    @Test
    fun convertsIsoCountryCodesToFlagEmojis() {
        assertEquals("🇨🇭", CountryFlagHelper.countryCodeToEmoji("CH"))
        assertEquals("🇨🇭", CountryFlagHelper.countryCodeToEmoji("ch"))
        assertEquals("🇹🇷", CountryFlagHelper.countryCodeToEmoji("TR"))
        assertEquals("🇹🇷", CountryFlagHelper.countryCodeToEmoji("tr"))
        assertEquals("🇧🇾", CountryFlagHelper.countryCodeToEmoji("BY"))
        assertEquals("🇮🇱", CountryFlagHelper.countryCodeToEmoji("IL"))
        assertEquals("🇮🇱", CountryFlagHelper.countryCodeToEmoji("il"))
        assertEquals("🇫🇷", CountryFlagHelper.countryCodeToEmoji("FR"))
        assertEquals("🇩🇪", CountryFlagHelper.countryCodeToEmoji("DE"))
        assertEquals("🌐", CountryFlagHelper.countryCodeToEmoji("UNKNOWN"))
        assertEquals("🌐", CountryFlagHelper.countryCodeToEmoji(null))
    }
}
