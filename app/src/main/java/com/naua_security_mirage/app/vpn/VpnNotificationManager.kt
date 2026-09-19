package com.naua_security_mirage.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.ui.MainActivity

class VpnNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildConnectedNotification(
        pingMs: Long,
        durationStr: String,
        downBps: Long = 0L,
        upBps: Long = 0L,
        isSpeedEnabled: Boolean = true
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(context, MirageVpnService::class.java).apply {
            action = MirageVpnService.ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            context,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (isSpeedEnabled) {
            String.format(
                context.getString(R.string.notification_connected_speed_text),
                formatSpeed(downBps),
                formatSpeed(upBps),
                if (pingMs > 0) pingMs else 0L,
                durationStr
            )
        } else {
            String.format(
                context.getString(R.string.notification_connected_text),
                if (pingMs > 0) pingMs else 0L,
                durationStr
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_stat)
            .setContentTitle(context.getString(R.string.notification_connected_title))
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                context.getString(R.string.action_disconnect),
                disconnectPendingIntent
            )
            .build()
    }

    fun buildDisconnectedNotification(): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val connectIntent = Intent(context, MirageVpnService::class.java).apply {
            action = MirageVpnService.ACTION_CONNECT
        }
        val connectPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context,
                2,
                connectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                2,
                connectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_stat)
            .setContentTitle(context.getString(R.string.notification_disconnected_title))
            .setContentText(context.getString(R.string.notification_disconnected_text))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                context.getString(R.string.action_connect),
                connectPendingIntent
            )
            .build()
    }

    fun updateConnected(
        pingMs: Long,
        durationStr: String,
        downBps: Long = 0L,
        upBps: Long = 0L,
        isSpeedEnabled: Boolean = true
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildConnectedNotification(pingMs, durationStr, downBps, upBps, isSpeedEnabled)
        )
    }

    fun showDisconnected() {
        notificationManager.notify(NOTIFICATION_ID, buildDisconnectedNotification())
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "mirage_vpn_status_channel"
        const val NOTIFICATION_ID = 1001

        fun formatSpeed(bytesPerSec: Long): String {
            return when {
                bytesPerSec <= 0 -> "0.0 КБ/с"
                bytesPerSec < 1024 -> "$bytesPerSec Б/с"
                bytesPerSec < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f КБ/с", bytesPerSec / 1024.0)
                bytesPerSec < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f МБ/с", bytesPerSec / (1024.0 * 1024.0))
                else -> String.format(java.util.Locale.US, "%.2f ГБ/с", bytesPerSec / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
}
