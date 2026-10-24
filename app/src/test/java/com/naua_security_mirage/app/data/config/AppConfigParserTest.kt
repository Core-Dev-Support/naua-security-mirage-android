package com.naua_security_mirage.app.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigParserTest {

    @Test
    fun serverPricesAndNodesAreParsed() {
        val raw = """
            {
              "version": "2026-10-24T14:02:36.504892+00:00",
              "plans": [
                {"id":"1m","days":30,"price_rub":50,"badge":null,"label_ru":"30 дней","label_en":"30 days"},
                {"id":"3m","days":90,"price_rub":140,"badge":"-7%","label_ru":"3 месяца","label_en":"3 months"},
                {"id":"6m","days":180,"price_rub":250,"badge":"-17%","label_ru":"6 месяцев","label_en":"6 months"},
                {"id":"12m","days":365,"price_rub":410,"badge":"-33%","label_ru":"1 год","label_en":"1 year"}
              ],
              "paid_nodes": [
                {"id":"premium_france","kind":"france","label_ru":"Франция (Платная)","label_en":"France (Paid)","flag":"france","config_function":"france-config","sort":10},
                {"id":"premium_finland","kind":"vless","label_ru":"Финляндия (Платная)","label_en":"Finland (Paid)","flag":"finland","config_function":"finland-config","sort":30}
              ],
              "min_price_rub": 50,
              "fallback": false
            }
        """.trimIndent()

        val config = parseAppConfig(raw, 123L) ?: error("config must parse")

        assertEquals(4, config.plans.size)
        assertEquals(50, config.plans.first { it.days == 30 }.priceRub)
        assertEquals(140, config.plans.first { it.days == 90 }.priceRub)
        assertEquals(250, config.plans.first { it.days == 180 }.priceRub)
        assertEquals(410, config.plans.first { it.days == 365 }.priceRub)
        assertEquals(50, config.minPriceRub)
        assertEquals(123L, config.fetchedAt)
        assertEquals("2026-10-24T14:02:36.504892+00:00", config.version)
        assertEquals(2, config.paidNodes.size)
        assertEquals(PaidNode.Kind.FRANCE, config.paidNodes[0].kind)
        assertEquals(PaidNode.Kind.VLESS, config.paidNodes[1].kind)
        assertEquals("finland-config", config.paidNodes[1].configFunction)
        assertEquals("Финляндия (Платная)", config.paidNodes[1].labelRu)
    }

    @Test
    fun anEmptyNodeListIsADeliberateState() {
        val config = parseAppConfig(
            """{"plans":[{"id":"1m","days":30,"price_rub":50}],"paid_nodes":[]}""",
            1L,
        ) ?: error("config must parse")

        assertTrue(config.paidNodes.isEmpty())
    }

    @Test
    fun anAbsentNodeListFallsBackToBuiltInNodes() {
        val config = parseAppConfig(
            """{"plans":[{"id":"1m","days":30,"price_rub":50}]}""",
            1L,
        ) ?: error("config must parse")

        assertEquals(AppConfig.DEFAULT_NODES.size, config.paidNodes.size)
    }

    @Test
    fun malformedOrEmptyPlansAreRejected() {
        assertNull(parseAppConfig("not json", 1L))
        assertNull(parseAppConfig("""{"plans":[]}""", 1L))
        assertNull(parseAppConfig("""{"plans":[{"id":"1m","days":0,"price_rub":50}]}""", 1L))
        assertNull(parseAppConfig("""{"plans":[{"id":"1m","days":30,"price_rub":0}]}""", 1L))
    }

    @Test
    fun invalidPlansAreSkippedWithoutDroppingValidOnes() {
        val config = parseAppConfig(
            """{"plans":[
                {"id":"1m","days":30,"price_rub":50},
                {"id":"bad","days":0,"price_rub":50},
                {"id":"1m","days":30,"price_rub":99}
            ]}""",
            1L,
        ) ?: error("config must parse")

        assertEquals(1, config.plans.size)
        assertEquals(50, config.plans[0].priceRub)
    }

    @Test
    fun fallbackFlagIsReadOnlyFromAJsonObject() {
        assertTrue(isAppConfigFallback("""{"fallback":true}"""))
        assertFalse(isAppConfigFallback("""{"fallback":false}"""))
        assertFalse(isAppConfigFallback("not json"))
    }
}
