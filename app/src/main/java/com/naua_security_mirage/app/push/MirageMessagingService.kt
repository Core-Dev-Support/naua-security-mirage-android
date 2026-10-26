package com.naua_security_mirage.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.data.supabase.SupabaseConfig
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.data.supabase.forSupabase
import com.naua_security_mirage.app.util.AppLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MirageMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        AppLogger.d(TAG, "Received a new messaging token")
        register(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        AppLogger.d(TAG, "Message received: ${message.notification?.title ?: "no title"}")
        val tag = message.data["tag"]
        if (!tag.isNullOrBlank()) {
            com.naua_security_mirage.app.work.UpdateNotifier.notifyIfNewer(applicationContext, tag)
        }
    }

    companion object {
        private const val TAG = "MirageMessaging"

        private val client = OkHttpClient.Builder()
            .forSupabase()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        fun register(token: String) {
            Thread {
                try {
                    // Токен сессии нужен, чтобы устройство привязалось
                    // к Google-аккаунту.
                    val payload = JSONObject()
                        .put("token", token)
                        .put("app_version", BuildConfig.VERSION_NAME)
                        .toString()
                    val bearer = SupabaseManager.instance.accessTokenOrNull()
                    val request = Request.Builder()
                        .url("${SupabaseConfig.SUPABASE_URL}/functions/v1/register-device")
                        .post(payload.toRequestBody("application/json".toMediaType()))
                        .header("User-Agent", "NAUA-Security-Mirage/${BuildConfig.VERSION_NAME}")
                        .apply {
                            if (!bearer.isNullOrEmpty()) {
                                header("Authorization", "Bearer $bearer")
                            }
                        }
                        .build()
                    client.newCall(request).execute().use { response ->
                        AppLogger.d(TAG, "Token registration: ${response.code}")
                    }
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "Token registration failed: ${t.message}")
                }
            }.start()
        }

        fun refresh() {
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance()
                    .token
                    .addOnCompleteListener { task ->
                        if (!task.isSuccessful) {
                            AppLogger.w(TAG, "Could not obtain a messaging token: ${task.exception?.message}")
                            return@addOnCompleteListener
                        }
                        val token = try {
                            task.result
                        } catch (t: Throwable) {
                            AppLogger.w(TAG, "Messaging token read failed: ${t.message}")
                            return@addOnCompleteListener
                        }
                        if (!token.isNullOrEmpty()) {
                            register(token)
                        } else {
                            AppLogger.w(TAG, "Could not obtain a messaging token")
                        }
                    }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Messaging unavailable: ${t.message}")
            }
        }
    }
}
