package com.naua_security_mirage.app.util

import android.content.Context
import java.io.File

/**
 * Чистка кэша, из-за которого приложение разрасталось до ~120 МБ:
 * скачанные APK обновлений, старые журналы и временные файлы.
 *
 * Удаляем только то, что уже не нужно для установки: свежий APK моложе
 * суток сохраняется, чтобы пользователь мог завершить обновление.
 */
object StorageCleanup {

    private const val TAG = "StorageCleanup"
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MAX_UPDATE_APKS = 2

    fun run(context: Context) {
        try {
            val reclaimed = cleanupUpdates(File(context.cacheDir, "updates")) +
                    cleanupSharedLogs(File(context.cacheDir, "mirage_logs")) +
                    cleanupTempFiles(context.cacheDir)
            LogHelper.enforceLogBudget(context)
            if (reclaimed > 0L) {
                AppLogger.i(TAG, "Очистка кэша: освобождено ${reclaimed / 1024L} КБ")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Очистка кэша не удалась: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun cleanupUpdates(dir: File): Long {
        if (!dir.isDirectory) return 0L
        val now = System.currentTimeMillis()
        var reclaimed = 0L
        val apks = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        // Оставляем только самые свежие APK, остальные — в корзину.
        apks.drop(MAX_UPDATE_APKS).forEach { file ->
            reclaimed += file.length()
            file.delete()
        }
        apks.take(MAX_UPDATE_APKS).forEach { file ->
            if (now - file.lastModified() > DAY_MS) {
                reclaimed += file.length()
                file.delete()
            }
        }

        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val old = now - file.lastModified() > DAY_MS
            val partial = file.name.endsWith(".part", true) ||
                    file.name.endsWith(".tmp", true) ||
                    file.name.endsWith(".download", true)
            if (old || partial) {
                reclaimed += file.length()
                file.delete()
            }
        }
        return reclaimed
    }

    private fun cleanupSharedLogs(dir: File): Long {
        if (!dir.isDirectory) return 0L
        val now = System.currentTimeMillis()
        var reclaimed = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isFile && now - file.lastModified() > DAY_MS) {
                reclaimed += file.length()
                file.delete()
            }
        }
        return reclaimed
    }

    private fun cleanupTempFiles(cacheDir: File): Long {
        if (!cacheDir.isDirectory) return 0L
        val now = System.currentTimeMillis()
        var reclaimed = 0L
        cacheDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val oldTemp = now - file.lastModified() > DAY_MS &&
                    (file.name.endsWith(".tmp", true) || file.name.endsWith(".part", true))
            if (oldTemp) {
                reclaimed += file.length()
                file.delete()
            }
        }
        return reclaimed
    }
}
