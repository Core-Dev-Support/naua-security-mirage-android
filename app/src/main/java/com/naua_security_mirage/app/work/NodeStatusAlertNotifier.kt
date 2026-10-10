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
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.MainActivity
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.vpn.NodeStatusReporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NodeStatusAlertNotifier {

    private const val TAG = "NodeAlert"
    private const val CHANNEL_ID = "mirage_nodes"
    private const val PREFS = "mirage_node_alerts"
    private const val KEY_ALERTED = "alerted_incident"
    private const val NOTIFICATION_ID = 1102
    private const val STALE_MS = 30 * 60 * 1000L

    suspend fun check(context: Context) {
        try {
            if (!SupabaseManager.instance.hasActiveSubscription()) return
            val status = NodeStatusReporter.fetch() ?: return
            onStatus(context, status)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Проверка состояния узла не удалась: ${t.message}")
        }
    }

    suspend fun onStatus(context: Context, status: NodeStatusReporter.Status) {
        try {
            if (!SupabaseManager.instance.hasActiveSubscription()) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val alerted = prefs.getString(KEY_ALERTED, "") ?: ""

            if (status.state == "up") {
                if (alerted.isNotEmpty()) {
                    if (canPost(context)) {
                        createChannel(context)
                        postRecovered(context, status.core ?: "?")
                    }
                    prefs.edit().remove(KEY_ALERTED).apply()
                    AppLogger.i(TAG, "Узел восстановился, отправлено уведомление о восстановлении")
                }
                return
            }

            if (status.state != "down" && status.state != "wedged") return

            val reported = status.reportedAt
            if (reported == null || System.currentTimeMillis() - reported > STALE_MS) {
                AppLogger.d(TAG, "Состояние протухло, уведомление пропущено")
                return
            }

            val incident = "${status.state}@${status.changedAt ?: -1L}"
            if (alerted == incident) return

            if (!canPost(context)) {
                AppLogger.d(TAG, "Уведомления не разрешены, пропуск")
                return
            }

            createChannel(context)
            val since = status.changedAt?.let { formatTime(it) } ?: "?"
            if (status.state == "down") {
                post(
                    context,
                    context.getString(R.string.node_alert_down_title),
                    context.getString(R.string.node_alert_down_body, since)
                )
            } else {
                post(
                    context,
                    context.getString(R.string.node_alert_wedged_title),
                    context.getString(R.string.node_alert_wedged_body, since)
                )
            }

            prefs.edit().putString(KEY_ALERTED, incident).apply()
            AppLogger.i(TAG, "Отправлено уведомление о сбое узла: $incident")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Уведомление о сбое узла не удалось: ${t.message}")
        }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(context: Context, title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SHOW_SETTINGS, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val largeIcon = try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        } catch (_: Throwable) {
            null
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.amber))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Не удалось показать уведомление: ${t.message}")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun postRecovered(context: Context, core: String) {
        post(
            context,
            context.getString(R.string.node_alert_recovered_title),
            context.getString(R.string.node_alert_recovered_body, core)
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val name = context.getString(R.string.node_alert_channel_name)
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(
                R.string.node_alert_channel_description,
                name,
            )
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun formatTime(epochMs: Long): String {
        return try {
            SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(epochMs))
        } catch (_: Throwable) {
            "?"
        }
    }
}
