package com.naua_security_mirage.app.data.config

import java.util.Locale

/**
 * Server-driven subscription plan. Prices live in `public.app_config` and are
 * served by the `app-config` edge function, so changing a price does not need
 * an APK release.
 */
data class SubscriptionPlan(
    val id: String,
    val days: Int,
    val priceRub: Int,
    val badge: String?,
    val labelRu: String,
    val labelEn: String
) {
    fun label(): String = if (isEnglish()) labelEn else labelRu
}

/**
 * A paid node shown in the switcher. `FRANCE` and `BELARUS` keep their
 * provider-specific key paths; `VLESS` is the generic path: the app calls
 * [configFunction] and parses the returned `vlessKey` with
 * [com.naua_security_mirage.app.data.model.VlessServer.fromUri].
 *
 * Adding a Finland entry to `app_config.paid_nodes` therefore creates a button
 * without a new APK, as long as `finland-config` returns a normal VLESS URI.
 */
data class PaidNode(
    val id: String,
    val kind: Kind,
    val labelRu: String,
    val labelEn: String,
    val flag: String,
    val configFunction: String,
    val sort: Int = 100
) {
    enum class Kind {
        FRANCE,
        BELARUS,
        VLESS;

        companion object {
            fun from(raw: String?): Kind = when (raw?.trim()?.lowercase(Locale.ROOT)) {
                "france" -> FRANCE
                "belarus" -> BELARUS
                else -> VLESS
            }
        }
    }

    fun label(): String = if (isEnglish()) labelEn else labelRu
}

data class AppConfig(
    val plans: List<SubscriptionPlan>,
    val paidNodes: List<PaidNode>,
    val minPriceRub: Int,
    val version: String?,
    val fetchedAt: Long
) {
    fun node(id: String?): PaidNode? =
        id?.let { wanted -> paidNodes.firstOrNull { it.id == wanted } }

    fun planForDays(days: Int): SubscriptionPlan? =
        plans.firstOrNull { it.days == days }

    fun planForPrice(priceRub: Int): SubscriptionPlan? =
        plans.firstOrNull { it.priceRub == priceRub }

    companion object {
        val DEFAULT_PLANS = listOf(
            SubscriptionPlan("1m", 30, 50, null, "30 дней", "30 days"),
            SubscriptionPlan("3m", 90, 140, "-7%", "3 месяца", "3 months"),
            SubscriptionPlan("6m", 180, 250, "-17%", "6 месяцев", "6 months"),
            SubscriptionPlan("12m", 365, 410, "-33%", "1 год", "1 year")
        )

        val DEFAULT_NODES = listOf(
            PaidNode(
                id = "premium_france",
                kind = PaidNode.Kind.FRANCE,
                labelRu = "Франция (Платная)",
                labelEn = "France (Paid)",
                flag = "france",
                configFunction = "france-config",
                sort = 10
            ),
            PaidNode(
                id = "premium_belarus",
                kind = PaidNode.Kind.BELARUS,
                labelRu = "Беларусь (Платная)",
                labelEn = "Belarus (Paid)",
                flag = "belarus",
                configFunction = "belarus-config",
                sort = 20
            )
        )

        fun defaults(): AppConfig = AppConfig(
            plans = DEFAULT_PLANS,
            paidNodes = DEFAULT_NODES,
            minPriceRub = DEFAULT_PLANS.minOfOrNull { it.priceRub } ?: 50,
            version = null,
            fetchedAt = 0L
        )
    }
}

internal fun isEnglish(): Boolean = Locale.getDefault().language == "en"
