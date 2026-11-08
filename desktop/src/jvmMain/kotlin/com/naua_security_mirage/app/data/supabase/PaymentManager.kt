package com.naua_security_mirage.app.data.supabase

import com.naua_security_mirage.app.util.AppLogger
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder

object PaymentManager {
    private const val TAG = "PaymentManager"

    fun buildYooMoneyUrl(
        userId: String,
        amountRub: Int = SupabaseConfig.subscriptionPriceRub,
        days: Int = 30
    ): String {
        val targets = URLEncoder.encode("Mirage VPN Подписка $days дней", "UTF-8")
        val successUrl = URLEncoder.encode("mirage://payment/success", "UTF-8")
        val label = "$userId:$days"

        return "https://yoomoney.ru/quickpay/confirm.xml" +
                "?receiver=${SupabaseConfig.yooMoneyWallet}" +
                "&quickpay-form=shop" +
                "&targets=$targets" +
                "&paymentType=AC" +
                "&sum=$amountRub" +
                "&label=$label" +
                "&successURL=$successUrl"
    }

    fun openPaymentBrowser(
        userId: String,
        amountRub: Int = SupabaseConfig.subscriptionPriceRub,
        days: Int = 30
    ): Boolean {
        return try {
            val url = buildYooMoneyUrl(userId, amountRub, days)
            AppLogger.info(TAG, "Opening YooMoney payment: ${amountRub} RUB, ${days} days")
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                val os = System.getProperty("os.name").lowercase()
                if (os.contains("win")) {
                    Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to open payment browser: ${e.message}")
            false
        }
    }
}
