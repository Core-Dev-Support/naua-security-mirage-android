package com.naua_security_mirage.app.data.supabase

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.util.AppLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PaymentManager {
    private const val TAG = "PaymentManager"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .forSupabase()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun createPaymentUrl(userId: String, days: Int = 30): String? {
        val baseUrl = SupabaseConfig.SUPABASE_URL.trimEnd('/')
        val payload = JSONObject()
            .put("userId", userId)
            .put("days", days)
            .put("source", "app")
            .toString()

        val request = Request.Builder()
            .url("$baseUrl/functions/v1/create-payment")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                AppLogger.error(TAG, "create-payment HTTP ${response.code}: $body")
                return null
            }
            val url = JSONObject(body).optString("payment_url", "").trim()
            return url.ifEmpty { null }
        }
    }

    fun openPaymentBrowser(
        context: Context,
        userId: String,
        amountRub: Int = SupabaseConfig.subscriptionPriceRub,
        days: Int = 30
    ): Boolean {
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        AppLogger.info(TAG, "Creating Cashera payment: $amountRub RUB, $days days")

        Thread {
            try {
                val url = createPaymentUrl(userId, days)
                if (url.isNullOrBlank()) {
                    mainHandler.post {
                        Toast.makeText(appContext, R.string.toast_payment_browser, Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                mainHandler.post {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        appContext.startActivity(intent)
                    } catch (e: Exception) {
                        AppLogger.error(TAG, "Failed to open payment browser: ${e.message}")
                        Toast.makeText(appContext, R.string.toast_payment_browser, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to create payment: ${e.message}")
                mainHandler.post {
                    Toast.makeText(appContext, R.string.toast_payment_browser, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()

        return true
    }
}
