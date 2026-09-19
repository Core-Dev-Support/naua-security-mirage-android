package com.naua_security_mirage.app.data.repository

import android.content.Context
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GeoRoutingRepository(private val context: Context) {

    private val settingsRepository = SettingsRepository(context)

    val geoDir: File = File(context.filesDir, "geo")
    val geositeFile: File = File(geoDir, "geosite.dat")
    val geoipFile: File = File(geoDir, "geoip.dat")

    fun isGeoFilesReady(): Boolean {
        return geositeFile.exists() && geositeFile.length() > 100_000 && hasCategoryRu(geositeFile) &&
                geoipFile.exists() && geoipFile.length() > 500_000
    }

    fun initFromAssetsIfNeeded() {
        try {
            if (!geoDir.exists()) {
                geoDir.mkdirs()
            }
            if (!geositeFile.exists() || !hasCategoryRu(geositeFile)) {
                context.assets.open("geo/geosite.dat").use { input ->
                    geositeFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                AppLogger.i(TAG, "База geosite.dat успешно скопирована из встроенных ресурсов приложения")
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Не удалось инициализировать гео-базу из assets: ${e.message}")
        }
    }

    fun cleanupInvalidFiles() {
        try {
            if (geositeFile.exists() && !hasCategoryRu(geositeFile)) {
                AppLogger.w(TAG, "Обнаружена несовместимая база geosite.dat (отсутствует category-ru). Удаление...")
                geositeFile.delete()
                settingsRepository.geoLastUpdateTime = 0L
            }
            initFromAssetsIfNeeded()
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Ошибка очистки устаревших гео-баз: ${e.message}")
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
                cleanupInvalidFiles()
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
            cleanupInvalidFiles()
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
                validator = { file -> hasCategoryRu(file) }
            )

            AppLogger.i(TAG, "Скачивание базы geoip.dat (база IP-адресов)...")
            downloadFileWithFallbacks(
                urls = listOf(
                    GEOIP_PRIMARY_URL,
                    GEOIP_FALLBACK_URL_1,
                    GEOIP_FALLBACK_URL_2
                ),
                targetFile = geoipFile,
                validator = { file -> file.length() > 500_000 }
            )

            val siteSizeMb = String.format(Locale.US, "%.1f", geositeFile.length() / (1024.0 * 1024.0))
            val ipSizeMb = String.format(Locale.US, "%.1f", geoipFile.length() / (1024.0 * 1024.0))
            settingsRepository.geoLastUpdateTime = System.currentTimeMillis()

            AppLogger.i(TAG, "Гео-базы успешно обновлены: geosite ($siteSizeMb МБ), geoip ($ipSizeMb МБ)")
            Result.success(true)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Ошибка скачивания гео-файлов: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun downloadFileWithFallbacks(
        urls: List<String>,
        targetFile: File,
        validator: (File) -> Boolean
    ) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        var success = false
        var lastError: Throwable? = null

        for (url in urls) {
            try {
                if (tempFile.exists()) tempFile.delete()
                downloadSingleUrl(url, tempFile)
                if (tempFile.exists() && validator(tempFile)) {
                    success = true
                    break
                } else {
                    AppLogger.w(TAG, "Файл из $url не прошел валидацию (размер: ${tempFile.length()} байт)")
                }
            } catch (e: Throwable) {
                lastError = e
                AppLogger.w(TAG, "Ошибка загрузки из $url: ${e.message}. Пробуем запасной источник...")
            }
        }

        if (!success || !tempFile.exists()) {
            tempFile.delete()
            throw IllegalStateException("Не удалось загрузить ${targetFile.name} ни из одного источника: ${lastError?.message}", lastError)
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun downloadSingleUrl(urlString: String, destination: File, maxRedirects: Int = 5) {
        var currentUrl = urlString
        var redirects = 0
        var connection: HttpURLConnection? = null
        try {
            while (redirects < maxRedirects) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 40_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; Mirage)")
                }

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (!location.isNullOrEmpty()) {
                        currentUrl = location
                        redirects++
                        continue
                    } else {
                        throw IllegalStateException("HTTP redirect $responseCode with no Location")
                    }
                }

                if (responseCode !in 200..299) {
                    throw IllegalStateException("HTTP error $responseCode for $currentUrl")
                }

                connection.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                }
                return
            }
            throw IllegalStateException("Too many redirects ($redirects) for $urlString")
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val TAG = "GeoRouting"
        private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

        // Official v2fly domain-list-community with CATEGORY-RU and TLD-RU
        private const val GEOSITE_PRIMARY_URL =
            "https://cdn.jsdelivr.net/gh/v2fly/domain-list-community@release/dlc.dat"
        private const val GEOSITE_FALLBACK_URL_1 =
            "https://raw.githubusercontent.com/v2fly/domain-list-community/release/dlc.dat"
        private const val GEOSITE_FALLBACK_URL_2 =
            "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"

        // GeoIP datasets containing Russian IP ranges and private subnets
        private const val GEOIP_PRIMARY_URL =
            "https://cdn.jsdelivr.net/gh/v2fly/geoip@release/geoip.dat"
        private const val GEOIP_FALLBACK_URL_1 =
            "https://raw.githubusercontent.com/v2fly/geoip/release/geoip.dat"
        private const val GEOIP_FALLBACK_URL_2 =
            "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geoip.dat"

        fun hasCategoryRu(file: File): Boolean {
            if (!file.exists() || file.length() < 100_000) return false
            return try {
                val target = "CATEGORY-RU".toByteArray(Charsets.US_ASCII)
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var matched = 0
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        for (i in 0 until bytesRead) {
                            if (buffer[i] == target[matched]) {
                                matched++
                                if (matched == target.size) return true
                            } else {
                                matched = if (buffer[i] == target[0]) 1 else 0
                            }
                        }
                    }
                    false
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}
