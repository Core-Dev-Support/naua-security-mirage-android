package com.naua_security_mirage.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateDnsModeTest {

    @Test
    fun readsAndroidTextValues() {
        assertEquals(PrivateDnsInspector.Mode.OFF, PrivateDnsInspector.modeFrom("off", -1))
        assertEquals(
            PrivateDnsInspector.Mode.OPPORTUNISTIC,
            PrivateDnsInspector.modeFrom("opportunistic", -1)
        )
        assertEquals(
            PrivateDnsInspector.Mode.STRICT,
            PrivateDnsInspector.modeFrom("hostname", -1)
        )
    }

    @Test
    fun toleratesCaseAndPadding() {
        assertEquals(PrivateDnsInspector.Mode.OFF, PrivateDnsInspector.modeFrom(" OFF ", -1))
        assertEquals(
            PrivateDnsInspector.Mode.STRICT,
            PrivateDnsInspector.modeFrom("Hostname", -1)
        )
    }

    @Test
    fun fallsBackToIntegerForm() {
        assertEquals(PrivateDnsInspector.Mode.OFF, PrivateDnsInspector.modeFrom(null, 0))
        assertEquals(PrivateDnsInspector.Mode.OPPORTUNISTIC, PrivateDnsInspector.modeFrom(null, 1))
        assertEquals(PrivateDnsInspector.Mode.STRICT, PrivateDnsInspector.modeFrom(null, 2))
    }

    @Test
    fun textWinsWhenBothPresent() {
        assertEquals(PrivateDnsInspector.Mode.STRICT, PrivateDnsInspector.modeFrom("hostname", 0))
    }

    @Test
    fun unknownStaysUnknown() {
        assertEquals(PrivateDnsInspector.Mode.UNKNOWN, PrivateDnsInspector.modeFrom(null, -1))
        assertEquals(PrivateDnsInspector.Mode.UNKNOWN, PrivateDnsInspector.modeFrom("nonsense", -1))
    }
}
