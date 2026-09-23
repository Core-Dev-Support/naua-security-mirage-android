package com.naua_security_mirage.app.data.repository

import java.io.File
import java.util.UUID

class DeviceIdRepository {
    private val appDataDir: File = run {
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        val dir = File(appData, "NAUA Security Mirage")
        if (!dir.exists()) dir.mkdirs()
        dir
    }
    private val idFile = File(appDataDir, "device_id.txt")

    fun getOrCreateDeviceId(): String {
        try {
            if (idFile.exists()) {
                val text = idFile.readText().trim()
                if (text.isNotEmpty()) {
                    UUID.fromString(text)
                    return text
                }
            }
        } catch (_: Exception) {}

        val newId = UUID.randomUUID().toString()
        try {
            idFile.writeText(newId)
        } catch (_: Exception) {}
        return newId
    }
}
