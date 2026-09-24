package com.naua_security_mirage.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.naua_security_mirage.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object AppLogger {

    const val LEVEL_SYSTEM = "SYSTEM"
    const val LEVEL_DEBUG = "DEBUG"
    const val LEVEL_INFO = "INFO"
    const val LEVEL_WARN = "WARN"
    const val LEVEL_ERROR = "ERROR"

    private const val MAX_LOG_LINES = 1000
    private val logBuffer = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val headerTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val _logFlow = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 200)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    private var settingsRepository: SettingsRepository? = null
    private var logFile: File? = null

    fun sanitize(text: String): String {
        var result = text
        // Mask specific server domain and endpoints
        result = result.replace(Regex("""[a-zA-Z0-9.-]*fantic\.top(:\d+)?""", RegexOption.IGNORE_CASE), "<redacted-server>")
        // Mask UUID credentials
        result = result.replace(Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"""), "********-****-****-****-************")
        // Mask Reality public keys
        result = result.replace(Regex("""[A-Za-z0-9_-]{43}="""), "*******************************************=")
        return result
    }

    fun init(context: Context) {
        settingsRepository = SettingsRepository(context)
        logFile = File(context.filesDir, "mirage_logs.txt")
        if (logBuffer.isEmpty() && logFile?.exists() == true) {
            try {
                logFile?.readLines()?.takeLast(MAX_LOG_LINES)?.forEach { line ->
                    logBuffer.add(line)
                }
            } catch (_: Exception) {}
        }
        system("MirageApp", "AppLogger initialized. Log level: ${settingsRepository?.logLevel}")
    }

    fun system(tag: String, message: String) {
        val safeMsg = sanitize(message)
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val formattedLine = "[$timestamp] [$LEVEL_SYSTEM] [$tag] $safeMsg"

        Log.i(tag, safeMsg)

        logBuffer.add(formattedLine)
        while (logBuffer.size > MAX_LOG_LINES) {
            logBuffer.pollFirst()
        }

        _logFlow.tryEmit(formattedLine)

        try {
            logFile?.appendText("$formattedLine\n")
        } catch (_: Exception) {}
    }

    fun d(tag: String, message: String) = log(LEVEL_DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LEVEL_INFO, tag, message)
    fun info(tag: String, message: String) = i(tag, message)
    fun w(tag: String, message: String, tr: Throwable? = null) = log(LEVEL_WARN, tag, if (tr != null) "$message: ${tr.message}" else message)
    fun e(tag: String, message: String, tr: Throwable? = null) = log(LEVEL_ERROR, tag, if (tr != null) "$message: ${tr.message}" else message)
    fun error(tag: String, message: String, tr: Throwable? = null) = e(tag, message, tr)

    fun log(level: String, tag: String, message: String) {
        val currentSetting = settingsRepository?.logLevel ?: SettingsRepository.LOG_LEVEL_AUTO

        if (currentSetting == SettingsRepository.LOG_LEVEL_NONE) {
            return
        }

        if (!shouldLog(level, currentSetting)) {
            return
        }

        val safeMsg = sanitize(message)
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val formattedLine = "[$timestamp] [$level] [$tag] $safeMsg"

        when (level) {
            LEVEL_DEBUG -> Log.d(tag, safeMsg)
            LEVEL_INFO -> Log.i(tag, safeMsg)
            LEVEL_WARN -> Log.w(tag, safeMsg)
            LEVEL_ERROR -> Log.e(tag, safeMsg)
            else -> Log.d(tag, safeMsg)
        }

        logBuffer.add(formattedLine)
        while (logBuffer.size > MAX_LOG_LINES) {
            logBuffer.pollFirst()
        }

        _logFlow.tryEmit(formattedLine)

        try {
            logFile?.appendText("$formattedLine\n")
        } catch (_: Exception) {}
    }

    private fun shouldLog(msgLevel: String, setting: String): Boolean {
        return when (setting) {
            SettingsRepository.LOG_LEVEL_NONE -> false
            SettingsRepository.LOG_LEVEL_ERROR -> msgLevel == LEVEL_ERROR
            SettingsRepository.LOG_LEVEL_WARNING -> msgLevel == LEVEL_ERROR || msgLevel == LEVEL_WARN
            SettingsRepository.LOG_LEVEL_INFO -> msgLevel == LEVEL_ERROR || msgLevel == LEVEL_WARN || msgLevel == LEVEL_INFO
            SettingsRepository.LOG_LEVEL_DEBUG -> true
            SettingsRepository.LOG_LEVEL_AUTO -> msgLevel == LEVEL_ERROR || msgLevel == LEVEL_WARN || msgLevel == LEVEL_INFO
            else -> true
        }
    }

    fun getAllLogs(): List<String> = logBuffer.toList()

    fun getAllLogsFormatted(): String {
        val setting = settingsRepository?.logLevel ?: SettingsRepository.LOG_LEVEL_AUTO
        val sb = StringBuilder()
        sb.append("======================================================\n")
        sb.append("  NAUA Security Mirage - System Diagnostics Log\n")
        sb.append("======================================================\n")
        sb.append("App Name     : NAUA Security Mirage\n")
        sb.append("Package      : com.naua_security_mirage.app\n")
        sb.append("App Version  : 1.0.0 (Release)\n")
        sb.append("Log Level    : $setting\n")
        sb.append("Export Time  : ${synchronized(headerTimeFormat) { headerTimeFormat.format(Date()) }}\n")
        sb.append("Device       : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("Android Ver  : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("CPU ABI      : ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        sb.append("======================================================\n\n")

        if (logBuffer.isEmpty()) {
            if (setting == SettingsRepository.LOG_LEVEL_NONE) {
                sb.append("(Сбор логов отключен в настройках)\n")
            } else {
                sb.append("(Записи логов отсутствуют)\n")
            }
        } else {
            logBuffer.forEach { line ->
                sb.append(line).append("\n")
            }
            if (setting == SettingsRepository.LOG_LEVEL_NONE) {
                sb.append("\n[SYSTEM] Сбор логов приостановлен (уровень None).\n")
            }
        }
        return sb.toString()
    }

    fun clear() {
        logBuffer.clear()
        try {
            logFile?.delete()
            logFile?.createNewFile()
        } catch (_: Exception) {}
        _logFlow.tryEmit("LOGS_CLEARED")
    }
}
