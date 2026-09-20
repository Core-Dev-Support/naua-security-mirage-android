package com.naua_security_mirage.app.util

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.repository.SettingsRepository
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AppUpdateManager: Unified update engine supporting GitHub Releases, RuStore, and Uptodown.
 * Provides in-app APK streaming download and PackageInstaller integration.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO = "Core-Dev-Support/naua-security-mirage-android"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val GITHUB_RELEASES_WEB = "https://github.com/$GITHUB_REPO/releases"

    data class UpdateInfo(
        val versionName: String,
        val tagName: String,
        val changelog: String,
        val downloadUrl: String,
        val apkFileName: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var latestUpdate: UpdateInfo? = null
        private set

    private var pendingInstallFile: File? = null
    private var activeDownloadCall: Call? = null

    private val updateListeners = mutableListOf<(Boolean, UpdateInfo?) -> Unit>()

    fun addUpdateListener(listener: (Boolean, UpdateInfo?) -> Unit) {
        if (!updateListeners.contains(listener)) {
            updateListeners.add(listener)
        }
        listener(latestUpdate != null, latestUpdate)
    }

    fun removeUpdateListener(listener: (Boolean, UpdateInfo?) -> Unit) {
        updateListeners.remove(listener)
    }

    private fun notifyListeners(hasUpdate: Boolean, info: UpdateInfo?) {
        mainHandler.post {
            for (listener in updateListeners) {
                try {
                    listener(hasUpdate, info)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error in update listener: ${e.message}")
                }
            }
        }
    }

    fun hasUpdate(): Boolean = latestUpdate != null

    fun checkForUpdates(
        activity: Activity,
        settingsRepository: SettingsRepository,
        isManual: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        when (settingsRepository.updateSource) {
            SettingsRepository.UPDATE_SOURCE_GITHUB -> {
                checkGitHubUpdates(activity, settingsRepository, isManual, onComplete)
            }
            SettingsRepository.UPDATE_SOURCE_RUSTORE -> {
                RuStoreUpdateHelper.checkForUpdates(activity, isManual = isManual)
                onComplete?.invoke()
            }
            SettingsRepository.UPDATE_SOURCE_UPTODOWN -> {
                handleUptodownUpdates(activity, isManual)
                onComplete?.invoke()
            }
            else -> {
                checkGitHubUpdates(activity, settingsRepository, isManual, onComplete)
            }
        }
    }

    private fun checkGitHubUpdates(
        activity: Activity,
        settingsRepository: SettingsRepository,
        isManual: Boolean,
        onComplete: (() -> Unit)?
    ) {
        Thread {
            try {
                val request = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "NAUA-Security-Mirage/${BuildConfig.VERSION_NAME}")
                    .build()

                val response = httpClient.newCall(request).execute()
                val statusCode = response.code
                val responseBody = response.body?.string()

                mainHandler.post {
                    onComplete?.invoke()
                    if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
                        handleGitHubReleaseResponse(activity, settingsRepository, responseBody, isManual)
                    } else if (statusCode == 404) {
                        latestUpdate = null
                        notifyListeners(false, null)
                        if (isManual) {
                            Toast.makeText(
                                activity,
                                "Репозиторий на GitHub в приватном режиме. Обновления будут доступны после публикации.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        if (isManual) {
                            Toast.makeText(
                                activity,
                                "Не удалось проверить обновления на GitHub (код $statusCode)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Ошибка проверки GitHub обновлений: ${e.message}")
                mainHandler.post {
                    onComplete?.invoke()
                    if (isManual) {
                        Toast.makeText(
                            activity,
                            "Ошибка сети при проверке обновлений. Проверьте интернет-соединение.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }.start()
    }

    private fun handleGitHubReleaseResponse(
        activity: Activity,
        settingsRepository: SettingsRepository,
        jsonString: String,
        isManual: Boolean
    ) {
        try {
            val json = JSONObject(jsonString)
            val tagName = json.optString("tag_name", "")
            val releaseName = json.optString("name", tagName)
            val body = json.optString("body", "")
            val htmlUrl = json.optString("html_url", GITHUB_RELEASES_WEB)

            var downloadUrl = htmlUrl
            var fileName = "NAUA-Security-Mirage-${tagName.trim().removePrefix("v").removePrefix("V")}.apk"

            val assets = json.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        // Prefer the signed NAUA-Security-Mirage-*.apk
                        downloadUrl = asset.optString("browser_download_url", downloadUrl)
                        fileName = name
                        if (name.startsWith("NAUA-Security-Mirage", ignoreCase = true)) {
                            break
                        }
                    }
                }
            }

            val currentVersion = BuildConfig.VERSION_NAME
            if (isNewerVersion(tagName, currentVersion)) {
                val cleanTag = tagName.trim()
                val info = UpdateInfo(
                    versionName = releaseName.ifEmpty { "Версия $cleanTag" },
                    tagName = cleanTag,
                    changelog = body.ifBlank { "Доступна новая версия приложения NAUA Security Mirage $cleanTag." },
                    downloadUrl = downloadUrl,
                    apkFileName = fileName
                )
                latestUpdate = info
                notifyListeners(true, info)
                if (isManual) {
                    showUpdateAvailableDialog(activity, info, settingsRepository)
                }
            } else {
                latestUpdate = null
                notifyListeners(false, null)
                if (isManual) {
                    Toast.makeText(
                        activity,
                        "У вас установлена последняя версия ($currentVersion)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка разбора ответа релиза: ${e.message}")
            if (isManual) {
                Toast.makeText(activity, "У вас установлена актуальная версия", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
        val cleanLatest = latestTag.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun handleUptodownUpdates(activity: Activity, isManual: Boolean) {
        latestUpdate = null
        notifyListeners(false, null)
        if (isManual) {
            Toast.makeText(
                activity,
                activity.getString(R.string.update_uptodown_status),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun showUpdateAvailableDialog(
        activity: Activity,
        updateInfo: UpdateInfo,
        settingsRepository: SettingsRepository? = null
    ) {
        val dialog = Dialog(activity)
        dialog.setContentView(R.layout.dialog_update_available)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val density = activity.resources.displayMetrics.density
        val hasWallpaper = settingsRepository != null &&
                !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository?.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository != null && settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )

        val cardBgColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(activity, R.color.card)
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0") else ContextCompat.getColor(activity, R.color.card_stroke)
        val titleTextColor = if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(activity, R.color.ink)
        val subtitleTextColor = if (isLightContext) Color.parseColor("#475569") else ContextCompat.getColor(activity, R.color.ink_soft)
        val accentColor = ContextCompat.getColor(activity, R.color.accent)

        val root = dialog.findViewById<LinearLayout>(R.id.dialogUpdateAvailableRoot)
        val ivIcon = dialog.findViewById<ImageView>(R.id.ivUpdateAvailableIcon)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvUpdateAvailableTitle)
        val tvBadge = dialog.findViewById<TextView>(R.id.tvUpdateVersionBadge)
        val vDivider = dialog.findViewById<View>(R.id.vUpdateAvailableDivider)
        val tvLabel = dialog.findViewById<TextView>(R.id.tvChangelogLabel)
        val tvChangelog = dialog.findViewById<TextView>(R.id.tvUpdateChangelog)
        val layoutProgress = dialog.findViewById<LinearLayout>(R.id.layoutUpdateProgress)
        val pbDownload = dialog.findViewById<ProgressBar>(R.id.pbUpdateDownload)
        val tvDownloadProgress = dialog.findViewById<TextView>(R.id.tvUpdateDownloadProgress)
        val btnLater = dialog.findViewById<TextView>(R.id.btnUpdateLater)
        val btnDownload = dialog.findViewById<TextView>(R.id.btnUpdateDownload)
        val btnBrowser = dialog.findViewById<TextView>(R.id.btnUpdateBrowser)

        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBgColor)
            setStroke((1.2f * density).toInt(), strokeColor)
        }

        tvTitle.setTextColor(titleTextColor)
        tvBadge.text = updateInfo.versionName
        tvBadge.setTextColor(accentColor)
        vDivider.setBackgroundColor(strokeColor)
        tvLabel.setTextColor(titleTextColor)
        tvChangelog.text = updateInfo.changelog
        tvChangelog.setTextColor(subtitleTextColor)
        ivIcon.imageTintList = ColorStateList.valueOf(accentColor)
        btnLater.setTextColor(subtitleTextColor)
        btnBrowser.setTextColor(subtitleTextColor)

        val updateDir = File(activity.cacheDir, "updates")
        val localApkFile = File(updateDir, updateInfo.apkFileName)

        btnDownload.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(ColorUtils.setAlphaComponent(accentColor, 35))
            setStroke((1f * density).toInt(), accentColor)
        }
        btnDownload.setTextColor(accentColor)

        if (localApkFile.exists() && localApkFile.length() > 5_000_000L) {
            btnDownload.text = activity.getString(R.string.update_dialog_install)
        } else {
            btnDownload.text = activity.getString(R.string.update_dialog_download)
        }

        btnLater.setOnClickListener {
            AnimationHelper.bounceClick(btnLater, minScale = 0.94f, durationMs = 150) {
                cancelDownload()
                dialog.dismiss()
            }
        }

        btnBrowser.setOnClickListener {
            AnimationHelper.bounceClick(btnBrowser, minScale = 0.96f, durationMs = 150) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(activity, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnDownload.setOnClickListener {
            AnimationHelper.bounceClick(btnDownload, minScale = 0.94f, durationMs = 150) {
                if (localApkFile.exists() && localApkFile.length() > 5_000_000L &&
                    btnDownload.text == activity.getString(R.string.update_dialog_install)
                ) {
                    installApk(activity, localApkFile)
                } else {
                    // Start in-app download
                    layoutProgress.visibility = View.VISIBLE
                    btnDownload.visibility = View.GONE
                    pbDownload.isIndeterminate = false
                    pbDownload.progress = 0
                    tvDownloadProgress.text = activity.getString(R.string.update_download_preparing)

                    downloadAndInstallApk(
                        activity = activity,
                        updateInfo = updateInfo,
                        onProgress = { percent, downloaded, total ->
                            if (percent >= 0) {
                                pbDownload.progress = percent
                                val dlFormatted = formatBytes(downloaded)
                                val totFormatted = formatBytes(total)
                                tvDownloadProgress.text = activity.getString(
                                    R.string.update_download_progress_format,
                                    percent,
                                    dlFormatted,
                                    totFormatted
                                )
                            } else {
                                pbDownload.isIndeterminate = true
                                tvDownloadProgress.text = "Загрузка: ${formatBytes(downloaded)}"
                            }
                        },
                        onComplete = { downloadedFile ->
                            layoutProgress.visibility = View.GONE
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.text = activity.getString(R.string.update_dialog_install)
                            btnDownload.setOnClickListener {
                                installApk(activity, downloadedFile)
                            }
                            installApk(activity, downloadedFile)
                        },
                        onError = { errorMsg ->
                            layoutProgress.visibility = View.GONE
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.text = activity.getString(R.string.update_dialog_download)
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.update_download_failed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
        }

        dialog.setOnDismissListener {
            cancelDownload()
        }

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 240)
    }

    fun downloadAndInstallApk(
        activity: Activity,
        updateInfo: UpdateInfo,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val updateDir = File(activity.cacheDir, "updates")
                if (!updateDir.exists()) {
                    updateDir.mkdirs()
                }
                val apkFile = File(updateDir, updateInfo.apkFileName)
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val request = Request.Builder()
                    .url(updateInfo.downloadUrl)
                    .header("User-Agent", "NAUA-Security-Mirage/${BuildConfig.VERSION_NAME}")
                    .build()

                val call = httpClient.newCall(request)
                activeDownloadCall = call
                val response = call.execute()

                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }

                val body = response.body ?: throw IllegalStateException("Empty body")
                val totalLength = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloaded = 0L
                var lastProgressReport = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloaded += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastProgressReport > 120 || downloaded == totalLength) {
                        lastProgressReport = now
                        val percent = if (totalLength > 0) ((downloaded * 100) / totalLength).toInt() else -1
                        mainHandler.post {
                            onProgress(percent, downloaded, totalLength)
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
                activeDownloadCall = null

                mainHandler.post {
                    onComplete(apkFile)
                }
            } catch (e: Exception) {
                activeDownloadCall = null
                AppLogger.w(TAG, "Ошибка загрузки APK: ${e.message}")
                mainHandler.post {
                    onError(e.message ?: "Unknown error")
                }
            }
        }.start()
    }

    fun cancelDownload() {
        try {
            activeDownloadCall?.cancel()
            activeDownloadCall = null
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error cancelling download: ${e.message}")
        }
    }

    fun installApk(activity: Activity, apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                Toast.makeText(activity, "Файл обновления поврежден", Toast.LENGTH_SHORT).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    pendingInstallFile = apkFile
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_install_permission_hint),
                        Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка запуска установки APK: ${e.message}")
            Toast.makeText(activity, "Не удалось запустить установщик: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun checkPendingInstall(activity: Activity) {
        val file = pendingInstallFile ?: return
        if (!file.exists()) {
            pendingInstallFile = null
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = null
            installApk(activity, file)
        }
    }

    fun showWhatsNewDialog(
        activity: Activity,
        settingsRepository: SettingsRepository
    ) {
        val currentVersion = BuildConfig.VERSION_NAME
        if (settingsRepository.lastShownWhatsNewVersion == currentVersion) {
            return
        }

        val dialog = Dialog(activity)
        dialog.setContentView(R.layout.dialog_update_available)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val density = activity.resources.displayMetrics.density
        val hasWallpaper = !settingsRepository.customBgImagePath.isNullOrEmpty() &&
                File(settingsRepository.customBgImagePath ?: "").exists()
        val isLightContext = !hasWallpaper && (
                settingsRepository.themePreset == SettingsRepository.THEME_LIGHT ||
                        (settingsRepository.customBgColor != 0 &&
                                ColorUtils.calculateLuminance(settingsRepository.customBgColor) > 0.5)
                )

        val cardBgColor = if (isLightContext) Color.WHITE else ContextCompat.getColor(activity, R.color.card)
        val strokeColor = if (isLightContext) Color.parseColor("#E2E8F0") else ContextCompat.getColor(activity, R.color.card_stroke)
        val titleTextColor = if (isLightContext) Color.parseColor("#0F172A") else ContextCompat.getColor(activity, R.color.ink)
        val subtitleTextColor = if (isLightContext) Color.parseColor("#475569") else ContextCompat.getColor(activity, R.color.ink_soft)
        val accentColor = ContextCompat.getColor(activity, R.color.accent)

        val root = dialog.findViewById<LinearLayout>(R.id.dialogUpdateAvailableRoot)
        val ivIcon = dialog.findViewById<ImageView>(R.id.ivUpdateAvailableIcon)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvUpdateAvailableTitle)
        val tvBadge = dialog.findViewById<TextView>(R.id.tvUpdateVersionBadge)
        val vDivider = dialog.findViewById<View>(R.id.vUpdateAvailableDivider)
        val tvLabel = dialog.findViewById<TextView>(R.id.tvChangelogLabel)
        val tvChangelog = dialog.findViewById<TextView>(R.id.tvUpdateChangelog)
        val layoutProgress = dialog.findViewById<LinearLayout>(R.id.layoutUpdateProgress)
        val btnLater = dialog.findViewById<TextView>(R.id.btnUpdateLater)
        val btnDownload = dialog.findViewById<TextView>(R.id.btnUpdateDownload)
        val btnBrowser = dialog.findViewById<TextView>(R.id.btnUpdateBrowser)

        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(cardBgColor)
            setStroke((1.2f * density).toInt(), strokeColor)
        }

        tvTitle.setTextColor(titleTextColor)
        tvTitle.text = activity.getString(R.string.whats_new_title)
        tvBadge.text = "Версия $currentVersion"
        tvBadge.setTextColor(accentColor)
        vDivider.setBackgroundColor(strokeColor)
        tvLabel.setTextColor(titleTextColor)
        tvLabel.text = "Главные изменения:"
        tvChangelog.text = activity.getString(R.string.whats_new_body)
        tvChangelog.setTextColor(subtitleTextColor)
        ivIcon.imageTintList = ColorStateList.valueOf(accentColor)

        layoutProgress.visibility = View.GONE
        btnLater.visibility = View.GONE
        btnBrowser.visibility = View.GONE

        btnDownload.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(ColorUtils.setAlphaComponent(accentColor, 35))
            setStroke((1f * density).toInt(), accentColor)
        }
        btnDownload.setTextColor(accentColor)
        btnDownload.text = "Понятно"

        btnDownload.setOnClickListener {
            AnimationHelper.bounceClick(btnDownload, minScale = 0.94f, durationMs = 150) {
                settingsRepository.lastShownWhatsNewVersion = currentVersion
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            settingsRepository.lastShownWhatsNewVersion = currentVersion
        }

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 240)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 0.1) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            val kb = bytes.toDouble() / 1024
            String.format(Locale.US, "%.0f KB", kb)
        }
    }
}
