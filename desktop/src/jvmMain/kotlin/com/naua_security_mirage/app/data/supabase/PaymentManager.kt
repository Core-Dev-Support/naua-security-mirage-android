package com.naua_security_mirage.app.data.supabase

import com.naua_security_mirage.app.util.AppLogger
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder

object PaymentManager {
    private const val TAG = "PaymentManager"

    /**
     * Builds the YooMoney P2P QuickPay URL.
     * [userId] is stored in the 'label' field so the webhook knows which user paid.
     */
    fun buildYooMoneyUrl(userId: String, amountRub: Int = SupabaseConfig.subscriptionPriceRub): String {
        val targets = URLEncoder.encode("Mirage VPN Подписка 30 дней", "UTF-8")
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

    /**
     * Opens the YooMoney checkout page in the user's default browser.
     */
    fun openPaymentBrowser(userId: String, amountRub: Int = SupabaseConfig.subscriptionPriceRub): Boolean {
        return try {
            val url = buildYooMoneyUrl(userId, amountRub)
            AppLogger.info(TAG, "Opening YooMoney payment URL: $url")
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to open payment browser: ${e.message}")
            false
        }
    }
}
