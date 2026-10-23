package com.naua_security_mirage.app.data.supabase

import com.naua_security_mirage.app.R

/**
 * Тарифы подписки.
 *
 * Список один и для бота, и для приложения: вебхук по сумме определяет, сколько
 * дней начислить, поэтому суммы здесь должны совпадать с теми, что знает сервер
 * (supabase/functions/yoomoney-webhook/contract.ts).
 *
 *   30 ₽  ->  30 дней
 *   80 ₽  ->  90 дней
 *   150 ₽ -> 180 дней
 *   250 ₽ -> 365 дней
 */
object SubscriptionPlans {

    data class Option(
        val months: Int,
        val days: Int,
        val amountRub: Int,
        /** Скидка для показа, как в боте: «-11%». */
        val discount: String?,
        /** Название срока в интерфейсе. */
        val titleRes: Int
    )

    val OPTIONS: List<Option> = listOf(
        Option(1, 30, 30, null, R.string.sub_option_1m),
        Option(3, 90, 80, "-11%", R.string.sub_option_3m),
        Option(6, 180, 150, "-17%", R.string.sub_option_6m),
        Option(12, 365, 250, "-32%", R.string.sub_option_12m)
    )
}
