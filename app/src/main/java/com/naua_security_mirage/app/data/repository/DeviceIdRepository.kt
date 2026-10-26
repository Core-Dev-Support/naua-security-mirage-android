package com.naua_security_mirage.app.data.repository

import android.content.Context
import android.provider.Settings
import java.nio.charset.StandardCharsets
import java.util.UUID

class DeviceIdRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("mirage_device_prefs", Context.MODE_PRIVATE)

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrEmpty()) {
            try {
                UUID.fromString(existing)
                return existing
            } catch (_: Exception) {

            }
        }

        val deviceId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }

    /**
     * Ключ устройства для серверной проверки «пробный день уже был».
     *
     * Обычный device_id живёт в настройках приложения и сбрасывается вместе с
     * ними, поэтому для запрета повторного пробника нужен ключ, который
     * переживает очистку данных: ANDROID_ID на Android 8+ уникален для
     * (подпись приложения, пользователь, устройство) и не меняется при
     * переустановке. Реклама с ним не связана, разрешение не нужно.
     */
    fun getDeviceFingerprint(): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) {
            null
        }
        val base = (androidId?.takeIf { it.isNotBlank() } ?: getOrCreateDeviceId()) +
                ":" + context.packageName
        return sha256(base)
    }

    private fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}
