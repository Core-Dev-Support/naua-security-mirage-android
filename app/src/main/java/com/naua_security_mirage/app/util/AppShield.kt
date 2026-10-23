package com.naua_security_mirage.app.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Диагностика среды запуска.
 *
 * Проверки только пишут отчёт и строку в лог. Приложение распространяется
 * APK-файлом, поэтому установщиком может быть MIUI, Honor, файловый менеджер
 * или Telegram. Раньше здесь был `Process.killProcess`, и любой такой
 * установщик давал «чёрный экран» ещё до отрисовки первого экрана.
 *
 * Целостность обновления проверяется отдельно в AppUpdateManager: перед
 * установкой сверяются package name и SHA-256 сертификата.
 */
object AppShield {

    private const val TAG = "AppShield"
    private const val REPORT_FILE = "shield_report.txt"

    @Volatile
    private var lastFindings: List<String> = emptyList()

    private val MASK_KEY = byteArrayOf(
        0x1D.toByte(), 0x91.toByte(), 0x6B.toByte(), 0x37.toByte(), 0xBA.toByte(), 0x5F.toByte(), 0xC6.toByte(), 0x14.toByte(),
        0xDD.toByte(), 0xDB.toByte(), 0xA0.toByte(), 0x7C.toByte(), 0xFD.toByte(), 0xEC.toByte(), 0x6E.toByte(), 0x26.toByte(),
        0xD6.toByte(), 0xB8.toByte(), 0x13.toByte(), 0xC7.toByte(), 0x8E.toByte(), 0x9F.toByte(), 0x7A.toByte(), 0x16.toByte(),
        0x24.toByte(), 0x62.toByte(), 0x50.toByte(), 0xC8.toByte(), 0x84.toByte(), 0xD9.toByte(), 0x2D.toByte(), 0x2F.toByte(),
        0x5F.toByte(), 0x9E.toByte(), 0xB1.toByte(), 0x95.toByte(), 0xFA.toByte(), 0x8E.toByte(), 0x31.toByte(), 0x6F.toByte(),
        0x6B.toByte(), 0x3C.toByte(), 0xE5.toByte(), 0x69.toByte(), 0x6B.toByte(), 0xF6.toByte(), 0x67.toByte(), 0xE6.toByte(),
        0x0B.toByte(), 0x5B.toByte(), 0xC7.toByte(), 0x67.toByte(), 0xC5.toByte(), 0x7B.toByte(), 0xA8.toByte(), 0x7D.toByte(),
        0x16.toByte(), 0x54.toByte(), 0x12.toByte(), 0x57.toByte(), 0x0E.toByte(), 0xFE.toByte(), 0x90.toByte(), 0x04.toByte()
    )

    private val OBFUSCATED_HASH = byteArrayOf(
        0x7C.toByte(), 0xA1.toByte(), 0x53.toByte(), 0x04.toByte(), 0x89.toByte(), 0x6F.toByte(), 0xF1.toByte(), 0x25.toByte(),
        0xB8.toByte(), 0xE3.toByte(), 0x95.toByte(), 0x4C.toByte(), 0x9C.toByte(), 0xD5.toByte(), 0x0C.toByte(), 0x45.toByte(),
        0xB7.toByte(), 0xD9.toByte(), 0x23.toByte(), 0xF3.toByte(), 0xBB.toByte(), 0xAB.toByte(), 0x4B.toByte(), 0x22.toByte(),
        0x47.toByte(), 0x52.toByte(), 0x65.toByte(), 0xAA.toByte(), 0xE2.toByte(), 0xBC.toByte(), 0x4C.toByte(), 0x1D.toByte(),
        0x6D.toByte(), 0xF8.toByte(), 0xD4.toByte(), 0xF0.toByte(), 0x98.toByte(), 0xBE.toByte(), 0x57.toByte(), 0x56.toByte(),
        0x09.toByte(), 0x0D.toByte(), 0xD7.toByte(), 0x50.toByte(), 0x58.toByte(), 0xCF.toByte(), 0x06.toByte(), 0xD7.toByte(),
        0x39.toByte(), 0x69.toByte(), 0xA4.toByte(), 0x50.toByte(), 0xA6.toByte(), 0x4A.toByte(), 0x91.toByte(), 0x4D.toByte(),
        0x24.toByte(), 0x63.toByte(), 0x27.toByte(), 0x6E.toByte(), 0x6C.toByte(), 0xCB.toByte(), 0xA8.toByte(), 0x31.toByte()
    )

    fun checkIntegrity(context: Context) {
        val findings = buildList {
            if (isSignaturesTampered(context)) add("signature")
            if (isDebuggableEnabled(context)) add("debuggable")
            if (isDebuggerAttached()) add("debugger")
            if (isTracerPidDetected()) add("tracer")
            if (isHookingFrameworkPresent()) add("hook")
            if (isFridaServerListening()) add("frida")
            if (isDeviceRooted()) add("root")
            if (isRunningOnEmulator()) add("emulator")
        }

        lastFindings = findings
        if (findings.isEmpty()) return

        Log.w(TAG, "Диагностика среды (не блокирует запуск): ${findings.joinToString()}")
        try {
            File(context.filesDir, REPORT_FILE).writeText(
                "findings=${findings.joinToString(",")}\ntime=${System.currentTimeMillis()}\n"
            )
        } catch (_: Throwable) {
        }
    }

    fun describedFindings(): String = lastFindings.joinToString(", ")

    private fun isSignaturesTampered(context: Context): Boolean {
        try {
            val pm = context.packageManager
            val packageName = context.packageName

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo ?: return true
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures.isNullOrEmpty()) {
                return true
            }

            val expectedHash = deobfuscateExpectedHash()

            for (sig in signatures) {
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(sig.toByteArray())
                val currentHash = digest.joinToString("") { "%02x".format(it) }
                if (currentHash.equals(expectedHash, ignoreCase = true)) {
                    return false
                }
            }

            return true
        } catch (_: Throwable) {
            return true
        }
    }

    private fun isDebuggableEnabled(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    private fun isTracerPidDetected(): Boolean {
        try {
            val statusFile = File("/proc/self/status")
            if (!statusFile.exists() || !statusFile.canRead()) return true

            BufferedReader(FileReader(statusFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line ?: continue
                    if (current.startsWith("TracerPid:")) {
                        val pidStr = current.substringAfter("TracerPid:").trim()
                        val pid = pidStr.toIntOrNull() ?: 0
                        return pid > 0
                    }
                }
            }
        } catch (_: Throwable) {
            return true
        }
        return false
    }

    private fun isHookingFrameworkPresent(): Boolean {
        try {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.exists() || !mapsFile.canRead()) return false

            val suspiciousPatterns = listOf(
                "frida",
                "xposed",
                "substrate",
                "libgadget",
                "libhook",
                "linjector"
            )

            BufferedReader(FileReader(mapsFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line?.lowercase() ?: continue
                    for (pattern in suspiciousPatterns) {
                        if (current.contains(pattern)) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            return true
        }
        return false
    }

    private fun isFridaServerListening(): Boolean {
        val ports = intArrayOf(27042, 27043)
        for (port in ports) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 75)
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun isDeviceRooted(): Boolean {
        val rootPaths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        for (path in rootPaths) {
            if (File(path).exists()) return true
        }
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true
        }
        return false
    }

    private fun isRunningOnEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val product = Build.PRODUCT.lowercase()

        if (fingerprint.startsWith("generic") || fingerprint.startsWith("unknown") ||
            model.contains("google_sdk") || model.contains("emulator") || model.contains("android sdk built for x86") ||
            manufacturer.contains("genymotion") ||
            hardware.contains("goldfish") || hardware.contains("ranchu") ||
            product.contains("sdk_gphone") || product.contains("google_sdk")
        ) {
            return true
        }
        return File("/dev/qemu_pipe").exists() || File("/dev/goldfish_pipe").exists()
    }

    private fun deobfuscateExpectedHash(): String {
        val decoded = ByteArray(OBFUSCATED_HASH.size)
        for (i in OBFUSCATED_HASH.indices) {
            decoded[i] = (OBFUSCATED_HASH[i].toInt() xor MASK_KEY[i % MASK_KEY.size].toInt()).toByte()
        }
        return String(decoded, Charsets.UTF_8)
    }
}
