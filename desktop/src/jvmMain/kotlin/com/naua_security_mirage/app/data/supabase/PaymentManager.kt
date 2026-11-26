package com.naua_security_mirage.app.data.supabase

import com.naua_security_mirage.app.util.AppLogger
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object PaymentManager {
    private const val TAG = "PaymentManager"
    private val paymentUrlRegex = Regex("\"payment_url\"\\s*:\\s*\"([^\"]+)\"")

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    fun createPaymentUrl(userId: String, days: Int = 30): String? {
        val baseUrl = SupabaseConfig.SUPABASE_URL.trimEnd('/')
        val conn = (URL("$baseUrl/functions/v1/create-payment").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        val payload = """{"userId":"${escapeJson(userId)}","days":$days,"source":"app"}"""
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            AppLogger.error(TAG, "create-payment HTTP $code: $body")
            return null
        }

        return paymentUrlRegex.find(body)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
    }

    fun openPaymentBrowser(
        userId: String,
        amountRub: Int = SupabaseConfig.subscriptionPriceRub,
        days: Int = 30
    ): Boolean {
        AppLogger.info(TAG, "Creating Cashera payment: $amountRub RUB, $days days")
        Thread {
            try {
                val url = createPaymentUrl(userId, days)
                if (url.isNullOrBlank()) return@Thread
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI(url))
                } else {
                    val os = System.getProperty("os.name").lowercase()
                    if (os.contains("win")) {
                        Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))
                    }
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to open payment browser: ${e.message}")
            }
        }.start()
        return true
    }
}
