package com.naua_security_mirage.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.ui.MainActivity
import com.naua_security_mirage.app.util.AppLogger

object UpdateNotifier {

    private const val TAG = "UpdateNotifier"
    private const val CHANNEL_ID = "mirage_updates"
    private const val PREFS = "mirage_update_worker"
    private const val KEY_NOTIFIED_TAG = "notified_tag"

    fun notifyIfNewer(context: Context, tag: String) {
        val clean = tag.trim()
        if (clean.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED_TAG, "") == clean) return

        if (!canPost(context)) {
            AppLogger.d(TAG, "Notifications not granted, skipping $clean")
            return
        }

        createChannel(context)
        post(context, clean)
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
    private fun post(context: Context, tag: String) {
        val label = tag.removePrefix("v").removePrefix("V")
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.update_available_title, label))
            .setContentText(context.getString(R.string.update_available_body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(tag.hashCode(), notification)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to show notification: ${t.message}")
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.update_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
