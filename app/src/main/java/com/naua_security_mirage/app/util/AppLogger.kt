package com.naua_security_mirage.app.util

import android.content.Context
import com.naua_security_mirage.app.R
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.naua_security_mirage.app.BuildConfig
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
    private const val MAX_LOG_FILE_BYTES = 2L * 1024L * 1024L
    private const val LOG_TAIL_BYTES = 256L * 1024L
    private const val FLUSH_EVERY_LINES = 16

    private val logBuffer = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val headerTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val jwtPattern =
        Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")
    private val domainPattern =
        Regex("""\b[a-zA-Z0-9-]{1,63}(\.[a-zA-Z0-9-]{1,63})*\.[a-zA-Z]{2,24}\b""")
    private val uuidPattern =
        Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val keyPattern = Regex("""[A-Za-z0-9_-]{43}=""")
    private val vlessPattern = Regex("""vless://[^\s"']+""", RegexOption.IGNORE_CASE)
    private val ipv4Pattern = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d{1,5})?\b""")
    private val hostPortPattern =
        Regex("""\b[A-Za-z0-9-]{1,63}(\.[A-Za-z0-9-]{1,63})+:\d{1,5}\b""")
    private val emailPattern = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    private val writerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "mirage-log-writer").apply { isDaemon = true }
    }
    @Volatile
    private var logWriter: java.io.BufferedWriter? = null
    @Volatile
    private var unflushedLines = 0

    private val _logFlow = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 200)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    private var settingsRepository: SettingsRepository? = null
    private var logFile: File? = null

    private var appContext: Context? = null

    private val registeredSecrets = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun registerSecrets(vararg values: String?) {
        synchronized(registeredSecrets) {
            values.forEach { value ->
                val v = value?.trim().orEmpty()

                if (v.length >= 6 && registeredSecrets.none { it == v }) {
                    registeredSecrets += v
                    // Токены меняются при каждом refresh — держим только свежие.
                    if (registeredSecrets.size > 32) registeredSecrets.removeAt(0)
                }
            }
        }
    }

    fun sanitize(text: String): String {
        var result = text

        synchronized(registeredSecrets) {
            registeredSecrets.forEach { secret ->
                result = result.replace(secret, "<redacted>")
            }
        }

        result = result.replace(jwtPattern, "<redacted-token>")

        // Email обрабатываем до домена: иначе \bdomain\b съест часть адреса
        // после @, и локальная часть останется в логе.
        result = result.replace(emailPattern, "<redacted-email>")

        result = result.replace(domainPattern, "<redacted-server>")

        result = result.replace(uuidPattern, "********-****-****-****-************")

        result = result.replace(keyPattern, "*******************************************=")

        result = result.replace(vlessPattern, "vless://<redacted-link>")

        result = result.replace(ipv4Pattern, "<redacted-endpoint>")
        result = result.replace(hostPortPattern, "<redacted-endpoint>")

        result = result.replace(emailPattern, "<redacted-email>")
        return result
    }

    fun init(context: Context) {
        settingsRepository = SettingsRepository(context)
        appContext = context.applicationContext

        registerSecrets(
            BuildConfig.FRANCE_SNI,
            BuildConfig.FRANCE_HOST,
            BuildConfig.YOOMONEY_WALLET,
            BuildConfig.SUPABASE_URL
        )
        logFile = File(context.filesDir, "mirage_logs.txt")
        if (logBuffer.isEmpty() && logFile?.exists() == true) {
            readTailLines(logFile!!, MAX_LOG_LINES).forEach { line -> logBuffer.add(line) }
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

        appendLineToFile(formattedLine)
    }

    private fun appendLineToFile(line: String) {
        val file = logFile ?: return
        writerExecutor.execute {
            try {
                var writer = logWriter
                if (writer == null) {
                    writer = java.io.FileOutputStream(file, true).bufferedWriter()
                    logWriter = writer
                }
                writer.append(line).append('\n')
                unflushedLines += 1
                if (unflushedLines >= FLUSH_EVERY_LINES) {
                    writer.flush()
                    unflushedLines = 0
                    if (file.length() > MAX_LOG_FILE_BYTES) {
                        try {
                            writer.close()
                        } catch (_: Exception) {}
                        logWriter = null
                        file.delete()
                        file.createNewFile()
                    }
                }
            } catch (_: Exception) {
                try {
                    logWriter?.close()
                } catch (_: Exception) {}
                logWriter = null
            }
        }
    }

    fun flushLogFile() {
        try {
            writerExecutor.submit {
                try {
                    logWriter?.flush()
                    unflushedLines = 0
                } catch (_: Exception) {}
            }.get(700, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
    }

    private fun readTailLines(file: File, maxLines: Int): List<String> = try {
        java.io.RandomAccessFile(file, "r").use { handle ->
            val length = handle.length()
            if (length == 0L) {
                emptyList()
            } else {
                val chunk = minOf(length, LOG_TAIL_BYTES).toInt()
                handle.seek(length - chunk)
                val bytes = ByteArray(chunk)
                handle.readFully(bytes)
                val parts = String(bytes, Charsets.UTF_8).split('\n')
                val usable = if (length > chunk) parts.drop(1) else parts
                usable.takeLast(maxLines).filter { it.isNotBlank() }
            }
        }
    } catch (_: Exception) {
        emptyList()
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

        appendLineToFile(formattedLine)
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

    fun onUserMessage(message: String) {
        val ctx = appContext ?: return
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, sanitize(message), Toast.LENGTH_LONG).show()
            }
        } catch (_: Throwable) {

        }
    }

    fun getAllLogs(): List<String> = logBuffer.toList()
    fun getAllLogsFormatted(): String {
        flushLogFile()
        val setting = settingsRepository?.logLevel ?: SettingsRepository.LOG_LEVEL_AUTO
        val sb = StringBuilder()
        sb.append("======================================================\n")
        sb.append("  NAUA Security Mirage - System Diagnostics Log\n")
        sb.append("======================================================\n")
        sb.append("App Name     : NAUA Security Mirage\n")
        sb.append("Package      : com.naua_security_mirage.app\n")
        sb.append("App Version  : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}/${BuildConfig.GIT_HASH})\n")
        sb.append("Log Level    : $setting\n")
        sb.append("Export Time  : ${synchronized(headerTimeFormat) { headerTimeFormat.format(Date()) }}\n")
        sb.append("Device       : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("Android Ver  : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("CPU ABI      : ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        sb.append("======================================================\n\n")

        if (logBuffer.isEmpty()) {
            if (setting == SettingsRepository.LOG_LEVEL_NONE) {
                sb.append(appContext?.getString(R.string.log_file_none) ?: "(Сбор логов отключен в настройках)").append("\n")
            } else {
                sb.append(appContext?.getString(R.string.log_file_empty) ?: "(Записи логов отсутствуют)").append("\n")
            }
        } else {
            logBuffer.forEach { line ->
                sb.append(line).append("\n")
            }
            if (setting == SettingsRepository.LOG_LEVEL_NONE) {
                sb.append("\n").append(appContext?.getString(R.string.log_file_paused) ?: "[SYSTEM] Сбор логов приостановлен (уровень None).").append("\n")
            }
        }
        return sb.toString()
    }

    fun clear() {
        logBuffer.clear()
        writerExecutor.execute {
            try {
                logWriter?.close()
            } catch (_: Exception) {}
            logWriter = null
            unflushedLines = 0
            try {
                logFile?.delete()
                logFile?.createNewFile()
            } catch (_: Exception) {}
        }
        _logFlow.tryEmit("LOGS_CLEARED")
    }
}
