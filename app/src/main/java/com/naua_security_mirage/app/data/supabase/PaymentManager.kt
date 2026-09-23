package com.naua_security_mirage.app.data.supabase

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.naua_security_mirage.app.util.AppLogger
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
     * Opens the YooMoney checkout page in the user's default browser on Android.
     */
    fun openPaymentBrowser(context: Context, userId: String, amountRub: Int = SupabaseConfig.subscriptionPriceRub): Boolean {
        return try {
            val url = buildYooMoneyUrl(userId, amountRub)
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
