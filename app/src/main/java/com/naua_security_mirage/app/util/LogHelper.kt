package com.naua_security_mirage.app.util

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

object LogHelper {
    private const val MAX_ERROR_CHARS = 60_000L
    private const val MAX_ACCESS_CHARS = 10_000L

    /**
     * Логи Xray растут без ограничения, а читать их целиком нельзя: на большом
     * файле приложение падало с OutOfMemoryError. Поэтому читаем только хвост
     * и заодно подрезаем сами файлы.
     */
    private const val MAX_XRAY_LOG_BYTES = 512L * 1024L

    fun collectLogs(context: Context): File {
        val logFile = File(context.cacheDir, "mirage_debug_logs.txt")

        val xrayError = File(context.cacheDir, "xray_error.log")
        val xrayAccess = File(context.cacheDir, "xray_access.log")

        val builder = StringBuilder()
        builder.append("--- XRAY ERROR LOG ---\n")
        if (xrayError.exists()) {
            builder.append(AppLogger.sanitize(readTail(xrayError, MAX_ERROR_CHARS)))
        } else {
            builder.append("No xray error log\n")
        }

        builder.append("\n--- XRAY ACCESS LOG ---\n")
        if (xrayAccess.exists()) {
            builder.append(AppLogger.sanitize(readTail(xrayAccess, MAX_ACCESS_CHARS)))
        } else {
            builder.append("No xray access log\n")
        }

        logFile.writeText(builder.toString())
        trimXrayLog(xrayError)
        trimXrayLog(xrayAccess)
        return logFile
    }

    /** Читает последние [maxBytes] байт файла, не загружая его целиком. */
    private fun readTail(file: File, maxBytes: Long): String {
        if (!file.exists()) return ""
        val length = file.length()
        if (length <= 0L) return ""
        val start = if (length > maxBytes) length - maxBytes else 0L
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val size = (length - start).toInt()
                val buffer = ByteArray(size)
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            ""
        }
    }

    /** Подрезает лог Xray, чтобы он не разрастался до гигабайта и не съедал память. */
    private fun trimXrayLog(file: File) {
        if (!file.exists()) return
        try {
            val length = file.length()
            if (length <= MAX_XRAY_LOG_BYTES) return
            val keep = (MAX_XRAY_LOG_BYTES / 2).toInt()
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - keep)
                val buffer = ByteArray(keep)
                raf.readFully(buffer)
                val trimmed = String(buffer, Charsets.UTF_8)
                file.writeText(trimmed, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
        }
    }

    /** Подрезает журналы Xray перед новым подключением. */
    fun trimXrayLogs(context: Context) {
        trimXrayLog(File(context.cacheDir, "xray_error.log"))
        trimXrayLog(File(context.cacheDir, "xray_access.log"))
    }
}
