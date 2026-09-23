package com.naua_security_mirage.app.util

import android.content.Context
import java.io.File

object LogHelper {
    fun collectLogs(context: Context): File {
        val logFile = File(context.cacheDir, "mirage_debug_logs.txt")
        val xrayError = File(context.cacheDir, "xray_error.log")
        val xrayAccess = File(context.cacheDir, "xray_access.log")
        
        val builder = StringBuilder()
        builder.append("--- XRAY ERROR LOG ---\n")
        if (xrayError.exists()) {
            builder.append(xrayError.readText())
        } else {
            builder.append("No xray error log\n")
        }
        
        builder.append("\n--- XRAY ACCESS LOG ---\n")
        if (xrayAccess.exists()) {
            builder.append(xrayAccess.readText().takeLast(10000))
        } else {
            builder.append("No xray access log\n")
        }
        
        builder.append("\n--- LOGCAT ---\n")
        try {
            val process = Runtime.getRuntime().exec("logcat -d -t 500")
            val logcat = process.inputStream.bufferedReader().readText()
            builder.append(logcat)
        } catch (e: Exception) {
            builder.append("Failed to get logcat: ${e.message}\n")
        }
        
        logFile.writeText(builder.toString())
        return logFile
    }
}
