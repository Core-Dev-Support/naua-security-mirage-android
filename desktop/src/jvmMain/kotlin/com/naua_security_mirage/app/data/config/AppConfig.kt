package com.naua_security_mirage.app.data.config

import java.util.Locale

/**
 * Server-driven subscription plan. Prices live in `public.app_config` and are
 * served by the `app-config` edge function.
 */
data class SubscriptionPlan(
    val id: String,
    val days: Int,
    val priceRub: Int,
    val badge: String?,
    val labelRu: String,
    val labelEn: String
) {
    fun label(isEnglish: Boolean = false): String = if (isEnglish) labelEn else labelRu
}

/**
 * A paid node shown in the switcher.
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

    fun label(isEnglish: Boolean = false): String = if (isEnglish) labelEn else labelRu
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
                labelRu = "Швейцария (Платная)",
                labelEn = "Switzerland (Paid)",
                flag = "switzerland",
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
