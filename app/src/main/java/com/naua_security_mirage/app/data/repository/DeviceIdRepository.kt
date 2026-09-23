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
                // Stored ID is not a valid UUID, regenerate
            }
        }

        val deviceId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}
