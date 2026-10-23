package com.naua_security_mirage.app.data.supabase

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.naua_security_mirage.app.util.AppLogger
import java.net.URLEncoder

object PaymentManager {
    private const val TAG = "PaymentManager"

    fun buildYooMoneyUrl(
        userId: String,
        amountRub: Int = SupabaseConfig.subscriptionPriceRub,
        days: Int = 30
    ): String {
        val isEnglish = java.util.Locale.getDefault().language == "en"
        val targets = URLEncoder.encode(
            if (isEnglish) "Mirage VPN Subscription $days days" else "Mirage VPN Подписка $days дней",
            "UTF-8"
        )
        val successUrl = URLEncoder.encode("mirage://payment/success", "UTF-8")

        return "https://yoomoney.ru/quickpay/confirm.xml" +
                "?receiver=${SupabaseConfig.yooMoneyWallet}" +
                "&quickpay-form=shop" +
                "&targets=$targets" +
                "&paymentType=AC" +
                "&sum=$amountRub" +
                "&label=$userId" +
                "&successURL=$successUrl"
    }

    fun openPaymentBrowser(
        context: Context,
        userId: String,
        amountRub: Int = SupabaseConfig.subscriptionPriceRub,
        days: Int = 30
    ): Boolean {
        return try {
            val url = buildYooMoneyUrl(userId, amountRub, days)
            AppLogger.info(TAG, "Opening YooMoney payment URL: $url")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to open payment browser: ${e.message}")
            false
        }
    }
}
