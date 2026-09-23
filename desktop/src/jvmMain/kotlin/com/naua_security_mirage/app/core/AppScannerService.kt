package com.naua_security_mirage.app.core

import com.naua_security_mirage.app.data.model.AppInfo
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AppScannerService {

    suspend fun scanInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val apps = mutableListOf<AppInfo>()
        val seenPaths = mutableSetOf<String>()

        try {
            val roots = listOf(
                File(System.getenv("ProgramFiles") ?: "C:\\Program Files"),
                File(System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"),
                File(System.getenv("LOCALAPPDATA") ?: "", "Programs")
            )

            for (root in roots) {
                if (!root.exists()) continue
                root.walkTopDown().maxDepth(3).filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }.forEach { file ->
                    val name = file.nameWithoutExtension
                    if (!isExcludedExe(name) && seenPaths.add(file.absolutePath.lowercase())) {
                        apps.add(
                            AppInfo(
                                packageName = file.absolutePath,
                                appName = formatAppName(name),
                                isSystemApp = false,
                                isProxied = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w("AppScanner", "Ошибка сканирования установленных приложений: ${e.message}")
        }

        if (apps.isEmpty()) {
            // Default common browsers and apps
            apps.add(AppInfo(appName = "Google Chrome", packageName = "chrome.exe", isProxied = true))
            apps.add(AppInfo(appName = "Mozilla Firefox", packageName = "firefox.exe", isProxied = true))
            apps.add(AppInfo(appName = "Microsoft Edge", packageName = "msedge.exe", isProxied = true))
            apps.add(AppInfo(appName = "Telegram Desktop", packageName = "telegram.exe", isProxied = true))
            apps.add(AppInfo(appName = "Discord", packageName = "discord.exe", isProxied = true))
            apps.add(AppInfo(appName = "Steam", packageName = "steam.exe", isProxied = true))
        }

        apps.sortedBy { it.appName.lowercase() }
    }

    private fun isExcludedExe(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("uninstall") ||
                lower.contains("update") ||
                lower.contains("installer") ||
                lower.contains("setup") ||
                lower.contains("helper") ||
                lower.contains("crashpad") ||
                lower.startsWith("unins")
    }

    private fun formatAppName(raw: String): String {
        return raw.replace(Regex("[-_]"), " ").split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}
