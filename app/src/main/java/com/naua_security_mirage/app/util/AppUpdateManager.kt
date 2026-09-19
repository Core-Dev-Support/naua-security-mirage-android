package com.naua_security_mirage.app.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AppUpdateManager: Unified update engine supporting GitHub Releases, RuStore, and Uptodown.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO = "Core-Dev-Support/naua-security-mirage-android"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val GITHUB_RELEASES_WEB = "https://github.com/$GITHUB_REPO/releases"
    private const val RUSTORE_CATALOG_URL = "https://www.rustore.ru/catalog/app/com.naua_security_mirage.app"
    private const val UPTODOWN_CATALOG_URL = "https://en.uptodown.com/android"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun checkForUpdates(
        activity: Activity,
        settingsRepository: SettingsRepository,
        isManual: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        when (settingsRepository.updateSource) {
            SettingsRepository.UPDATE_SOURCE_GITHUB -> {
                checkGitHubUpdates(activity, isManual, onComplete)
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
                checkGitHubUpdates(activity, isManual, onComplete)
            }
        }
    }

    private fun checkGitHubUpdates(
        activity: Activity,
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
                        handleGitHubReleaseResponse(activity, responseBody, isManual)
                    } else if (statusCode == 404) {
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
            val assets = json.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", downloadUrl)
                        break
                    }
                }
            }

            val currentVersion = BuildConfig.VERSION_NAME
            if (isNewerVersion(tagName, currentVersion)) {
                showUpdateAvailableDialog(activity, releaseName.ifEmpty { tagName }, body, downloadUrl)
            } else {
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
        if (isManual) {
            val dialog = Dialog(activity)
            dialog.setContentView(R.layout.dialog_update_available)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.90f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            val tvTitle = dialog.findViewById<TextView>(R.id.tvUpdateAvailableTitle)
            val tvBadge = dialog.findViewById<TextView>(R.id.tvUpdateVersionBadge)
            val tvLabel = dialog.findViewById<TextView>(R.id.tvChangelogLabel)
            val tvBody = dialog.findViewById<TextView>(R.id.tvUpdateChangelog)
            val btnLater = dialog.findViewById<TextView>(R.id.btnUpdateLater)
            val btnDownload = dialog.findViewById<TextView>(R.id.btnUpdateDownload)
            val root = dialog.findViewById<LinearLayout>(R.id.dialogUpdateAvailableRoot)

            tvTitle?.text = "Uptodown App Store"
            tvBadge?.text = "Статус: На модерации"
            tvLabel?.text = "Информация:"
            tvBody?.text = "Приложение NAUA Security Mirage отправлено в каталог Uptodown App Store и сейчас проходит процедуру проверки и модерации.\n\nКак только публикация завершится, обновления станут доступны для скачивания."
            btnDownload?.text = "Перейти на сайт"

            btnLater?.setOnClickListener { dialog.dismiss() }
            btnDownload?.setOnClickListener {
                dialog.dismiss()
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UPTODOWN_CATALOG_URL))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(activity, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                }
            }

            dialog.show()
            AnimationHelper.popIn(root, durationMs = 240)
        }
    }

    fun showUpdateAvailableDialog(
        activity: Activity,
        versionName: String,
        changelog: String,
        downloadUrl: String
    ) {
        val dialog = Dialog(activity)
        dialog.setContentView(R.layout.dialog_update_available)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.90f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val root = dialog.findViewById<LinearLayout>(R.id.dialogUpdateAvailableRoot)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvUpdateAvailableTitle)
        val tvBadge = dialog.findViewById<TextView>(R.id.tvUpdateVersionBadge)
        val tvBody = dialog.findViewById<TextView>(R.id.tvUpdateChangelog)
        val btnLater = dialog.findViewById<TextView>(R.id.btnUpdateLater)
        val btnDownload = dialog.findViewById<TextView>(R.id.btnUpdateDownload)

        tvTitle?.text = "Доступно обновление"
        tvBadge?.text = versionName
        tvBody?.text = changelog.ifBlank { "Доступна новая версия приложения с улучшениями стабильности и новыми функциями." }

        btnLater?.setOnClickListener {
            AnimationHelper.bounceClick(btnLater, minScale = 0.94f, durationMs = 150) {
                dialog.dismiss()
            }
        }

        btnDownload?.setOnClickListener {
            AnimationHelper.bounceClick(btnDownload, minScale = 0.94f, durationMs = 150) {
                dialog.dismiss()
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(activity, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
        AnimationHelper.popIn(root, durationMs = 240)
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
            (activity.resources.displayMetrics.widthPixels * 0.90f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val root = dialog.findViewById<LinearLayout>(R.id.dialogUpdateAvailableRoot)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvUpdateAvailableTitle)
        val tvBadge = dialog.findViewById<TextView>(R.id.tvUpdateVersionBadge)
        val tvLabel = dialog.findViewById<TextView>(R.id.tvChangelogLabel)
        val tvBody = dialog.findViewById<TextView>(R.id.tvUpdateChangelog)
        val btnLater = dialog.findViewById<TextView>(R.id.btnUpdateLater)
        val btnDownload = dialog.findViewById<TextView>(R.id.btnUpdateDownload)

        tvTitle?.text = activity.getString(R.string.whats_new_title)
        tvBadge?.text = "Версия $currentVersion"
        tvLabel?.text = "Главные изменения:"
        tvBody?.text = activity.getString(R.string.whats_new_body)
        btnLater?.visibility = View.GONE
        btnDownload?.text = "Понятно"

        btnDownload?.setOnClickListener {
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
}
