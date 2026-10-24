package com.naua_security_mirage.app.data.config

import com.google.gson.annotations.SerializedName
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
            SubscriptionPlan("1m", 30, 40, null, "30 дней", "30 days"),
            SubscriptionPlan("3m", 90, 110, "-8%", "3 месяца", "3 months"),
            SubscriptionPlan("6m", 180, 200, "-17%", "6 месяцев", "6 months"),
            SubscriptionPlan("12m", 365, 330, "-32%", "1 год", "1 year")
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
            minPriceRub = DEFAULT_PLANS.minOfOrNull { it.priceRub } ?: 40,
            version = null,
            fetchedAt = 0L
        )
    }
}

internal data class AppConfigDto(
    @SerializedName("version") val version: String? = null,
    @SerializedName("plans") val plans: List<PlanDto>? = null,
    @SerializedName("paid_nodes") val paidNodes: List<NodeDto>? = null,
    @SerializedName("min_price_rub") val minPriceRub: Int? = null,
    @SerializedName("fallback") val fallback: Boolean = false
)

internal data class PlanDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("days") val days: Int? = null,
    @SerializedName("price_rub") val priceRub: Int? = null,
    @SerializedName("badge") val badge: String? = null,
    @SerializedName("label_ru") val labelRu: String? = null,
    @SerializedName("label_en") val labelEn: String? = null
)

internal data class NodeDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("label_ru") val labelRu: String? = null,
    @SerializedName("label_en") val labelEn: String? = null,
    @SerializedName("flag") val flag: String? = null,
    @SerializedName("config_function") val configFunction: String? = null,
    @SerializedName("sort") val sort: Int? = null
)

internal fun isEnglish(): Boolean = Locale.getDefault().language == "en"
