package com.naua_security_mirage.app.util

import android.content.Context
import com.naua_security_mirage.app.BuildConfig
import java.io.File

object LogHelper {
    private const val MAX_ERROR_CHARS = 60_000
    private const val MAX_ACCESS_CHARS = 10_000

    fun collectLogs(context: Context): File {
        val logFile = File(context.cacheDir, "mirage_debug_logs.txt")
        if (!BuildConfig.LOGS_ENABLED) {
            logFile.writeText("Диагностика недоступна в релизной сборке.\n")
            return logFile
        }

        val xrayError = File(context.cacheDir, "xray_error.log")
        val xrayAccess = File(context.cacheDir, "xray_access.log")

        val builder = StringBuilder()
        builder.append("--- XRAY ERROR LOG ---\n")
        if (xrayError.exists()) {

            builder.append(AppLogger.sanitize(xrayError.readText().takeLast(MAX_ERROR_CHARS)))
        } else {
            builder.append("No xray error log\n")
        }

        builder.append("\n--- XRAY ACCESS LOG ---\n")
        if (xrayAccess.exists()) {
            builder.append(AppLogger.sanitize(xrayAccess.readText().takeLast(MAX_ACCESS_CHARS)))
        } else {
            builder.append("No xray access log\n")
        }

        logFile.writeText(builder.toString())
        return logFile
    }
}
