package com.naua_security_mirage.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.receiver.UpdateDownloadReceiver
import com.naua_security_mirage.app.ui.MainActivity
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.util.AppUpdateManager
import java.io.File
import java.util.Locale

object UpdateNotifier {

    private const val TAG = "UpdateNotifier"
    private const val CHANNEL_UPDATES_ID = "mirage_updates"
    private const val CHANNEL_DOWNLOAD_ID = "mirage_download_progress"
    private const val PREFS = "mirage_update_worker"
    private const val KEY_NOTIFIED_TAG = "notified_tag"

    private const val NOTIFICATION_UPDATE_ID = 1001
    private const val NOTIFICATION_DOWNLOAD_ID = 1002

    fun notifyIfNewer(context: Context, tag: String) {
        val clean = tag.trim()
        if (clean.isEmpty()) return

        if (!AppUpdateManager.isNewerVersion(clean, BuildConfig.VERSION_NAME)) {
            AppLogger.d(TAG, "Tag $clean is not newer than ${BuildConfig.VERSION_NAME}, skipping notification")
            return
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED_TAG, "") == clean) return

        if (!canPost(context)) {
            AppLogger.d(TAG, "Notifications not granted, skipping $clean")
            return
        }

        createUpdatesChannel(context)
        postUpdateAvailable(context, clean)
        prefs.edit().putString(KEY_NOTIFIED_TAG, clean).apply()
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun postUpdateAvailable(context: Context, tag: String) {
        val label = tag.removePrefix("v").removePrefix("V")
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
            putExtra(MainActivity.EXTRA_UPDATE_TAG, tag)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val largeIcon = try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        } catch (_: Throwable) {
            null
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setContentTitle(context.getString(R.string.update_available_title, label))
            .setContentText(context.getString(R.string.update_available_body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE_ID, builder.build())
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to show update notification: ${t.message}")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun showDownloadProgress(
        context: Context,
        versionName: String,
        percent: Int,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        if (!canPost(context)) return
        createDownloadChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_DOWNLOAD_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(context, UpdateDownloadReceiver::class.java).apply {
            action = UpdateDownloadReceiver.ACTION_CANCEL_DOWNLOAD
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_DOWNLOAD_ID,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isIndeterminate = percent < 0 || totalBytes <= 0
        val text = if (!isIndeterminate) {
            val dlFormatted = formatBytes(downloadedBytes)
            val totFormatted = formatBytes(totalBytes)
            context.getString(R.string.update_download_progress_format, percent, dlFormatted, totFormatted)
        } else {
            "Загрузка: ${formatBytes(downloadedBytes)}"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setContentTitle(context.getString(R.string.update_downloading_title, versionName))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_close,
                context.getString(R.string.action_cancel),
                cancelPendingIntent
            )

        if (isIndeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent, false)
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_DOWNLOAD_ID, builder.build())
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to update download progress notification: ${t.message}")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun showDownloadComplete(
        context: Context,
        versionName: String,
        apkFile: File
    ) {
        if (!canPost(context)) return
        createUpdatesChannel(context)

        val installPendingIntent = createInstallPendingIntent(context, apkFile)

        val largeIcon = try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        } catch (_: Throwable) {
            null
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setContentTitle(context.getString(R.string.update_download_complete_title))
            .setContentText(context.getString(R.string.update_download_complete_body, versionName))
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .setContentIntent(installPendingIntent)
            .addAction(
                R.drawable.ic_download,
                context.getString(R.string.update_dialog_install),
                installPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        try {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(NOTIFICATION_DOWNLOAD_ID)
            manager.notify(NOTIFICATION_DOWNLOAD_ID, builder.build())
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to show download complete notification: ${t.message}")
        }
    }

    fun cancelDownloadNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_DOWNLOAD_ID)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to cancel download notification: ${t.message}")
        }
    }

    private fun createInstallPendingIntent(context: Context, apkFile: File): PendingIntent {
        val canDirectInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()

        return if (canDirectInstall) {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            PendingIntent.getActivity(
                context,
                apkFile.hashCode(),
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_INSTALL_APK_PATH, apkFile.absolutePath)
            }
            PendingIntent.getActivity(
                context,
                apkFile.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
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

    private fun createUpdatesChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_UPDATES_ID,
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.update_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun createDownloadChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_DOWNLOAD_ID,
            context.getString(R.string.update_download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.update_download_channel_desc)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
