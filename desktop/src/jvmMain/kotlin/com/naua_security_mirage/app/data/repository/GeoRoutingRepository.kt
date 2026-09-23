package com.naua_security_mirage.app.data.repository

import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GeoRoutingRepository(private val settingsRepository: SettingsRepository) {

    private val appDataDir: File = run {
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        val dir = File(appData, "NAUA Security Mirage")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    val geoDir: File = File(appDataDir, "geo")
    val geositeFile: File = File(geoDir, "geosite.dat")
    val geoipFile: File = File(geoDir, "geoip.dat")

    fun isGeoFilesReady(): Boolean {
        return geositeFile.exists() && geositeFile.length() > 100_000 &&
                geoipFile.exists() && geoipFile.length() > 500_000
    }

    fun initFromBundledIfNeeded() {
        try {
            if (!geoDir.exists()) {
                geoDir.mkdirs()
            }
            val coreDir = File(System.getProperty("user.dir"), "Core")
            val bundledGeosite = if (File(coreDir, "geosite.dat").exists()) {
                File(coreDir, "geosite.dat")
            } else {
                File("desktop/Core/geosite.dat")
            }
            val bundledGeoip = if (File(coreDir, "geoip.dat").exists()) {
                File(coreDir, "geoip.dat")
            } else {
                File("desktop/Core/geoip.dat")
            }

            if ((!geositeFile.exists() || geositeFile.length() < 100_000) && bundledGeosite.exists()) {
                bundledGeosite.copyTo(geositeFile, overwrite = true)
                AppLogger.i(TAG, "База geosite.dat скопирована из встроенных ресурсов")
            }
            if ((!geoipFile.exists() || geoipFile.length() < 500_000) && bundledGeoip.exists()) {
                bundledGeoip.copyTo(geoipFile, overwrite = true)
                AppLogger.i(TAG, "База geoip.dat скопирована из встроенных ресурсов")
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Не удалось инициализировать гео-базы из встроенных ресурсов: ${e.message}")
        }
    }

    fun getLastUpdateFormatted(): String {
        val lastUpdate = settingsRepository.geoLastUpdateTime
        val timestamp = if (lastUpdate > 0L) {
            lastUpdate
        } else if (geositeFile.exists() && geositeFile.lastModified() > 0L) {
            geositeFile.lastModified()
        } else {
            0L
        }

        return if (timestamp > 0L && isGeoFilesReady()) {
            val sdf = SimpleDateFormat("d MMM HH:mm", Locale("ru"))
            sdf.format(Date(timestamp))
        } else {
            "Не обновлялись"
        }
    }

    suspend fun autoUpdateIfNeeded() {
        withContext(Dispatchers.IO) {
            try {
                initFromBundledIfNeeded()
                val now = System.currentTimeMillis()
                val lastUpdate = settingsRepository.geoLastUpdateTime
                val isOutdated = (now - lastUpdate) > UPDATE_INTERVAL_MS

                if (!isGeoFilesReady() || isOutdated) {
                    AppLogger.i(TAG, "Запуск фонового обновления гео-баз маршрутизации...")
                    updateGeoFiles(force = false)
                }
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Ошибка автообновления гео-баз: ${e.message}")
            }
        }
    }

    suspend fun updateGeoFiles(force: Boolean = false): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            initFromBundledIfNeeded()
            if (!force && isGeoFilesReady()) {
                val now = System.currentTimeMillis()
                val lastUpdate = settingsRepository.geoLastUpdateTime
                if ((now - lastUpdate) < UPDATE_INTERVAL_MS) {
                    return@withContext Result.success(false)
                }
            }

            if (!geoDir.exists()) {
                geoDir.mkdirs()
            }

            AppLogger.i(TAG, "Скачивание базы geosite.dat (список доменов с категориями РФ)...")
            downloadFileWithFallbacks(
                urls = listOf(
                    GEOSITE_PRIMARY_URL,
                    GEOSITE_FALLBACK_URL_1,
                    GEOSITE_FALLBACK_URL_2
                ),
                targetFile = geositeFile,
                minSize = 100_000
            )

            AppLogger.i(TAG, "Скачивание базы geoip.dat (диапазоны IP-адресов РФ)...")
            downloadFileWithFallbacks(
                urls = listOf(
                    GEOIP_PRIMARY_URL,
                    GEOIP_FALLBACK_URL_1,
                    GEOIP_FALLBACK_URL_2
                ),
                targetFile = geoipFile,
                minSize = 500_000
            )

            settingsRepository.geoLastUpdateTime = System.currentTimeMillis()
            AppLogger.i(TAG, "Гео-базы успешно обновлены до актуальной версии!")
            Result.success(true)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Не удалось обновить базы маршрутизации: ${e.message}")
            Result.failure(e)
        }
    }

    private fun downloadFileWithFallbacks(
        urls: List<String>,
        targetFile: File,
        minSize: Long
    ) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.download")
        var success = false

        for (urlStr in urls) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "NAUA-Security-Mirage-PC/1.2.0")

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (tempFile.exists() && tempFile.length() >= minSize) {
                        if (targetFile.exists()) targetFile.delete()
                        tempFile.renameTo(targetFile)
                        success = true
                        break
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Ошибка загрузки с $urlStr: ${e.message}")
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }

        if (!success && !targetFile.exists()) {
            throw IllegalStateException("Failed to download ${targetFile.name} from all mirrors")
        }
    }

    companion object {
        private const val TAG = "GeoRoutingRepo"
        private const val UPDATE_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

        private const val GEOSITE_PRIMARY_URL =
            "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
        private const val GEOSITE_FALLBACK_URL_1 =
            "https://raw.githubusercontent.com/v2fly/domain-list-community/release/dlc.dat"
        private const val GEOSITE_FALLBACK_URL_2 =
            "https://cdn.jsdelivr.net/gh/v2fly/domain-list-community@release/dlc.dat"

        private const val GEOIP_PRIMARY_URL =
            "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
        private const val GEOIP_FALLBACK_URL_1 =
            "https://raw.githubusercontent.com/v2fly/geoip/release/geoip.dat"
        private const val GEOIP_FALLBACK_URL_2 =
            "https://cdn.jsdelivr.net/gh/v2fly/geoip@release/geoip.dat"
    }
}
