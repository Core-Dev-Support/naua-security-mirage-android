package com.naua_security_mirage.app.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.MirageApp
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.data.supabase.forSupabase
import com.naua_security_mirage.app.work.UpdateNotifier
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO = "Core-Dev-Support/naua-security-mirage-android"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val GITHUB_RELEASES_WEB = "https://github.com/$GITHUB_REPO/releases"
    private const val CHANGELOG_URL = "https://github.com/$GITHUB_REPO/blob/main/CHANGELOG.md"
    // Текущий APK — около 30 МБ. Лимит с запасом защищает от бесконечного
    // ответа и от подмены файла на гигантский архив.
    private const val MAX_UPDATE_BYTES = 200L * 1024L * 1024L

    data class UpdateInfo(
        val versionName: String,
        val tagName: String,
        val changelog: String,
        val downloadUrl: String,
        val apkFileName: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .forSupabase()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // OkHttp сам идёт по редиректам GitHub. Сетевой interceptor проверяет
    // каждый фактический запрос, поэтому увести загрузку на чужой хост
    // через подменённый Location нельзя.
    private val downloadClient = httpClient.newBuilder()
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!isAllowedDownloadUrl(url.toString())) {
                throw java.io.IOException("Update download host is not allowed: ${url.host}")
            }
            chain.proceed(chain.request())
        }
        .build()

    var latestUpdate: UpdateInfo? = null
        private set

    private var pendingInstallFile: File? = null
    private var activeDownloadCall: Call? = null
    var onDownloadCancelled: (() -> Unit)? = null

    @Volatile
    private var downloadCancelledByUser = false

    @Volatile
    private var updateDialogShowing = false

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
                val proxyUrl =
                    "${com.naua_security_mirage.app.data.supabase.SupabaseConfig.SUPABASE_URL}/functions/v1/latest-release"

                var request = Request.Builder()
                    .url(proxyUrl)
                    .header("User-Agent", "NAUA-Security-Mirage/${BuildConfig.VERSION_NAME}")
                    .build()

                var response = httpClient.newCall(request).execute()
                if (!response.isSuccessful && response.code != 404) {
                    response.close()
                    Log.w(TAG, "Прокси релизов ответил ${response.code}, пробуем GitHub напрямую")
                    request = Request.Builder()
                        .url(GITHUB_API_URL)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "NAUA-Security-Mirage/${BuildConfig.VERSION_NAME}")
                        .build()
                    response = httpClient.newCall(request).execute()
                }

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
                                R.string.update_github_private,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else if (statusCode == 403) {
                        latestUpdate = null
                        notifyListeners(false, null)
                        if (isManual) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.update_github_rate_limit),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        if (isManual) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.update_github_code_fmt, statusCode),
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
                            activity.getString(R.string.update_network_error),
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

            // В релизе может лежать несколько APK (arm64, armeabi-v7a,
            // universal). Берём тот, который подходит текущему устройству,
            // иначе Android откажется устанавливать чужую ABI.
            pickBestApkAsset(json.optJSONArray("assets"))?.let { (assetUrl, assetName) ->
                downloadUrl = assetUrl
                fileName = sanitizeApkFileName(assetName)
            }
            if (!isAllowedDownloadUrl(downloadUrl)) {
                downloadUrl = GITHUB_RELEASES_WEB
            }

            val currentVersion = BuildConfig.VERSION_NAME
            if (isNewerVersion(tagName, currentVersion)) {
                val cleanTag = tagName.trim()
                val info = UpdateInfo(
                    versionName = releaseName.ifEmpty { activity.getString(R.string.update_version_tag_fmt, cleanTag) },
                    tagName = cleanTag,
                    changelog = body.ifBlank { activity.getString(R.string.update_version_available_fmt, cleanTag) },
                    downloadUrl = downloadUrl,
                    apkFileName = fileName
                )
                latestUpdate = info
                notifyListeners(true, info)
                showUpdateAvailableDialog(activity, info, settingsRepository)
            } else {
                latestUpdate = null
                notifyListeners(false, null)
                if (isManual) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_up_to_date_fmt, currentVersion),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка разбора ответа релиза: ${e.message}")
            if (isManual) {
                Toast.makeText(activity, R.string.toast_up_to_date, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Выбирает APK под ABI устройства. Для старых релизов с одним файлом
     * возвращает его, для новых — arm64/armeabi-v7a/universal.
     */
    private fun pickBestApkAsset(assets: JSONArray?): Pair<String, String>? {
        if (assets == null || assets.length() == 0) return null
        val supported = Build.SUPPORTED_ABIS.map { it.lowercase(Locale.ROOT) }
        val hasArm64 = supported.contains("arm64-v8a")
        val hasArm32 = supported.any { it == "armeabi-v7a" || it == "armeabi" }

        var bestScore = 0
        var best: Pair<String, String>? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url", "")
            if (!isAllowedDownloadUrl(url)) continue
            val score = apkAssetScore(name.lowercase(Locale.ROOT), hasArm64, hasArm32)
            if (score > bestScore) {
                bestScore = score
                best = url to name
            }
        }
        return best
    }

    private fun apkAssetScore(name: String, hasArm64: Boolean, hasArm32: Boolean): Int {
        val isArm64 = name.contains("arm64-v8a") || name.contains("arm64")
        val isArm32 = name.contains("armeabi-v7a") || name.contains("arm-v7a") || name.contains("v7a")
        val isX86 = name.contains("x86")
        return when {
            isArm64 && hasArm64 -> 100
            isArm32 && hasArm32 -> if (hasArm64) 30 else 90
            name.contains("universal") -> 50
            !isArm64 && !isArm32 && !isX86 -> 40
            else -> 0
        }
    }

    private fun sanitizeApkFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val clean = base.filter { it.isLetterOrDigit() || it in "._-" }.take(120)
        return if (clean.length > 4 && clean.endsWith(".apk", ignoreCase = true)) clean
        else "NAUA-Security-Mirage-update.apk"
    }

    private fun isAllowedDownloadUrl(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (_: Throwable) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.userInfo.isNullOrBlank()) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "github.com" ||
                host == "www.github.com" ||
                host == "api.github.com" ||
                host == "objects.githubusercontent.com" ||
                host == "github-releases.githubusercontent.com" ||
                host == "release-assets.githubusercontent.com"
    }

    fun isNewerVersion(latestTag: String, currentVersion: String): Boolean =
        VersionComparator.isNewer(latestTag, currentVersion)

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
        if (updateDialogShowing) return
        updateDialogShowing = true
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
        if (updateInfo.changelog.trimStart().startsWith("Что нового", ignoreCase = true) ||
            updateInfo.changelog.trimStart().startsWith("What's new", ignoreCase = true)) {
            tvLabel.visibility = View.GONE
        } else {
            tvLabel.visibility = View.VISIBLE
        }
        tvChangelog.text = updateInfo.changelog
        tvChangelog.setTextColor(subtitleTextColor)
        ivIcon.imageTintList = ColorStateList.valueOf(accentColor)
        dialog.findViewById<TextView>(R.id.tvChangelogFullLink).apply {
            setTextColor(accentColor)
            setOnClickListener {
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHANGELOG_URL)))
                }
            }
        }
        val dangerColor = ContextCompat.getColor(activity, R.color.danger)
        btnLater.setTextColor(dangerColor)
        btnLater.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(ColorUtils.setAlphaComponent(dangerColor, 28))
            setStroke((1f * density).toInt(), ColorUtils.setAlphaComponent(dangerColor, 140))
        }
        btnBrowser.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(cardBgColor)
            setStroke((1f * density).toInt(), strokeColor)
        }
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
            btnLater.text = activity.getString(R.string.update_dialog_close)
            btnBrowser.visibility = View.GONE
        } else {
            btnDownload.text = activity.getString(R.string.update_dialog_download)
            btnLater.text = activity.getString(R.string.update_dialog_later)
            btnBrowser.visibility = View.VISIBLE
        }

        btnLater.setOnClickListener {
            AnimationHelper.bounceClick(btnLater, minScale = 0.94f, durationMs = 150) {
                val downloading = layoutProgress.visibility == View.VISIBLE
                cancelDownload()
                if (!downloading) dialog.dismiss()
            }
        }

        btnBrowser.setOnClickListener {
            AnimationHelper.bounceClick(btnBrowser, minScale = 0.96f, durationMs = 150) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(activity, R.string.toast_browser_fail, Toast.LENGTH_SHORT).show()
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

                    layoutProgress.visibility = View.VISIBLE
                    btnDownload.visibility = View.GONE
                    btnBrowser.visibility = View.GONE
                    pbDownload.isIndeterminate = false
                    pbDownload.progress = 0
                    tvDownloadProgress.text = activity.getString(R.string.update_download_preparing)

                    onDownloadCancelled = {
                        mainHandler.post {
                            layoutProgress.visibility = View.GONE
                            btnDownload.visibility = View.VISIBLE
                            btnBrowser.visibility = View.VISIBLE
                            btnDownload.text = activity.getString(R.string.update_dialog_download)
                        }
                    }

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
                                tvDownloadProgress.text = activity.getString(R.string.download_progress_unknown_fmt, formatBytes(downloaded))
                            }
                        },
                        onComplete = { downloadedFile ->
                            onDownloadCancelled = null
                            layoutProgress.visibility = View.GONE
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.text = activity.getString(R.string.update_dialog_install)
                            btnDownload.setOnClickListener {
                                installApk(activity, downloadedFile)
                            }
                            installApk(activity, downloadedFile)
                        },
                        onError = { _ ->
                            onDownloadCancelled = null
                            layoutProgress.visibility = View.GONE
                            btnDownload.visibility = View.VISIBLE
                            btnBrowser.visibility = View.VISIBLE
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
            updateDialogShowing = false
            onDownloadCancelled = null
            cancelDownload(activity)
        }

        dialog.show()
        clampChangelogScroll(dialog, root)
        AnimationHelper.popIn(root, durationMs = 240)
    }

    private fun clampChangelogScroll(dialog: Dialog, root: View) {
        val scroll = dialog.findViewById<ScrollView>(R.id.scrollUpdateChangelog) ?: return
        AnimationHelper.clampScrollToBudget(dialog, root, scroll)
    }

    fun downloadAndInstallApk(
        activity: Activity,
        updateInfo: UpdateInfo,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        downloadCancelledByUser = false
        Thread {
            val updateDir = File(activity.cacheDir, "updates")
            if (!updateDir.exists()) {
                updateDir.mkdirs()
            }
            val apkFile = File(updateDir, updateInfo.apkFileName)
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val appContext = activity.applicationContext
            UpdateNotifier.showDownloadProgress(
                context = appContext,
                versionName = updateInfo.versionName,
                percent = 0,
                downloadedBytes = 0L,
                totalBytes = -1L
            )

            try {
                if (!isAllowedDownloadUrl(updateInfo.downloadUrl)) {
                    throw java.io.IOException("Update download URL is not allowed")
                }
                val request = Request.Builder()
                    .url(updateInfo.downloadUrl)
                    .header("User-Agent", "NAUA-Security-Mirage/${BuildConfig.VERSION_NAME}")
                    .build()

                val call = downloadClient.newCall(request)
                activeDownloadCall = call
                val response = call.execute()

                // Проверяем конечный URL после редиректов, а не только стартовый:
                // GitHub отдаёт файлы через release-assets.githubusercontent.com,
                // но произвольный редирект на другой хост не должен проходить.
                val finalUrl = response.request.url.toString()
                if (!isAllowedDownloadUrl(finalUrl)) {
                    response.close()
                    throw java.io.IOException("Update redirect is not allowed: host")
                }

                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }

                val body = response.body ?: throw IllegalStateException("Empty body")
                val totalLength = body.contentLength()
                if (totalLength > MAX_UPDATE_BYTES) {
                    throw java.io.IOException("Update is too large: $totalLength bytes")
                }
                FileOutputStream(apkFile).use { outputStream ->
                    body.byteStream().use { inputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloaded = 0L
                        var lastProgressReport = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (downloaded > MAX_UPDATE_BYTES) {
                                throw java.io.IOException("Update exceeded the size limit")
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastProgressReport > 120 || downloaded == totalLength) {
                                lastProgressReport = now
                                val percent = if (totalLength > 0) ((downloaded * 100) / totalLength).toInt() else -1
                                mainHandler.post {
                                    onProgress(percent, downloaded, totalLength)
                                }
                                UpdateNotifier.showDownloadProgress(
                                    context = appContext,
                                    versionName = updateInfo.versionName,
                                    percent = percent,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalLength
                                )
                            }
                        }
                        outputStream.flush()
                    }
                }
                activeDownloadCall = null

                if (!verifyApkSignature(activity, apkFile)) {
                    apkFile.delete()
                    UpdateNotifier.cancelDownloadNotification(appContext)
                    mainHandler.post {
                        onError(activity.getString(R.string.update_sig_invalid))
                    }
                    return@Thread
                }

                UpdateNotifier.showDownloadComplete(
                    context = appContext,
                    versionName = updateInfo.versionName,
                    apkFile = apkFile
                )

                mainHandler.post {
                    onComplete(apkFile)
                }
            } catch (e: Exception) {
                activeDownloadCall = null
                UpdateNotifier.cancelDownloadNotification(appContext)

                val isCanceled = downloadCancelledByUser ||
                        e is java.io.InterruptedIOException ||
                        e is java.net.SocketException ||
                        e is java.net.SocketTimeoutException ||
                        e.message?.contains("Canceled", ignoreCase = true) == true ||
                        e.message?.contains("Socket closed", ignoreCase = true) == true

                if (isCanceled) {
                    AppLogger.d(TAG, "Загрузка обновления отменена пользователем")
                    if (apkFile.exists()) {
                        apkFile.delete()
                    }
                } else {
                    AppLogger.w(TAG, "Ошибка загрузки APK: ${e.message}")
                    mainHandler.post {
                        onError(e.message ?: "Unknown error")
                    }
                }
                if (apkFile.exists()) {
                    apkFile.delete()
                }
            }
        }.start()
    }

    fun cancelDownload(context: Context? = null) {
        try {
            downloadCancelledByUser = true
            activeDownloadCall?.cancel()
            activeDownloadCall = null
            val ctx = context ?: MirageApp.appContextOrNull
            ctx?.let { UpdateNotifier.cancelDownloadNotification(it) }
            onDownloadCancelled?.invoke()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error cancelling download: ${e.message}")
        }
    }

    private fun verifyApkSignature(activity: Activity, apkFile: File): Boolean {
        return try {
            val expected = installedSignerSha256(activity)
            if (expected.isEmpty()) {
                AppLogger.e(TAG, "Проверка обновления прервана: не удалось прочитать сертификат текущей сборки")
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_no_sig),
                    Toast.LENGTH_LONG
                ).show()
                return false
            }

            val archivePackage = packageNameOf(activity, apkFile)
            if (archivePackage == null) {
                AppLogger.e(TAG, "Проверка обновления прервана: файл не является корректным APK")
                Toast.makeText(activity, R.string.update_file_corrupt, Toast.LENGTH_LONG).show()
                apkFile.delete()
                return false
            }
            if (archivePackage != activity.packageName) {
                AppLogger.e(TAG, "Проверка обновления прервана: пакет $archivePackage вместо ${activity.packageName}")
                Toast.makeText(activity, R.string.update_foreign_package, Toast.LENGTH_LONG).show()
                apkFile.delete()
                return false
            }

            // Запрещаем откат на APK с меньшим versionCode. Сравниваем с
            // реально установленной версией, а не с константой сборки.
            // Равенство разрешено: 1.4.0 (5) может пересобираться без смены кода.
            val installedCode = installedVersionCode(activity)
            val archiveCode = archiveVersionCode(activity, apkFile)
            if (installedCode != null && archiveCode != null && archiveCode < installedCode) {
                AppLogger.e(
                    TAG,
                    "Проверка обновления прервана: versionCode $archiveCode ниже установленного $installedCode"
                )
                Toast.makeText(activity, R.string.update_verify_fail, Toast.LENGTH_LONG).show()
                apkFile.delete()
                return false
            }

            val archiveSigners = archiveSignerSha256(activity, apkFile)
            if (archiveSigners.isEmpty() || archiveSigners.none { it in expected }) {
                AppLogger.e(
                    TAG,
                    "Проверка обновления прервана: подпись APK не соответствует подписи приложения"
                )
                Toast.makeText(
                    activity,
                    R.string.update_sig_install_cancelled,
                    Toast.LENGTH_LONG
                ).show()
                apkFile.delete()
                return false
            }

            AppLogger.i(TAG, "Подпись обновления проверена: пакет и сертификат совпадают с текущей сборкой")
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Ошибка проверки подписи обновления: ${t.message}", t)
            Toast.makeText(activity, R.string.update_verify_fail, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun installedVersionCode(context: Context): Long? {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun archiveVersionCode(context: Context, apkFile: File): Long? {
        val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun packageNameOf(context: Context, apkFile: File): String? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            0
        }
        val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null
        @Suppress("DEPRECATION")
        return info.packageName
    }

    private fun installedSignerSha256(context: Context): Set<String> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            0
        }
        val info = pm.getPackageInfo(context.packageName, flags) ?: return emptySet()
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            val current = signingInfo.apkContentsSigners
            val history = signingInfo.signingCertificateHistory
            (current ?: emptyArray()) + (history ?: emptyArray())
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return certs.mapNotNull { cert ->
            try {
                sha256Hex(cert.toByteArray())
            } catch (_: Throwable) {
                null
            }
        }.toSet()
    }

    private fun archiveSignerSha256(context: Context, apkFile: File): Set<String> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            0
        }
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return emptySet()
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            val current = signingInfo.apkContentsSigners
            val history = signingInfo.signingCertificateHistory
            (current ?: emptyArray()) + (history ?: emptyArray())
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return certs.mapNotNull { cert ->
            try {
                sha256Hex(cert.toByteArray())
            } catch (_: Throwable) {
                null
            }
        }.toSet()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append("%02x".format(b))
        }
        return sb.toString()
    }

    fun installApk(activity: Activity, apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                Toast.makeText(activity, R.string.update_file_corrupt, Toast.LENGTH_SHORT).show()
                return
            }

            if (!verifyApkSignature(activity, apkFile)) {
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
            Toast.makeText(activity, activity.getString(R.string.update_installer_fail_fmt, e.message), Toast.LENGTH_LONG).show()
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
