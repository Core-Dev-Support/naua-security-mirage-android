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
import com.naua_security_mirage.app.data.supabase.SubscriptionPolicy
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.MainActivity
import com.naua_security_mirage.app.util.AppLogger
import java.util.concurrent.TimeUnit

object SubscriptionExpiryNotifier {

    private const val TAG = "SubscriptionExpiry"
    private const val CHANNEL_ID = "mirage_subscription"
    private const val PREFS = "mirage_subscription_expiry"
    private const val KEY_LAST_NOTIFIED_FOR = "last_notified_for"
    private const val KEY_NOTIFIED_STEPS = "notified_steps"
    private const val NOTIFICATION_ID = 1101

    fun check(context: Context) {
        try {
            val paidUntil = SupabaseManager.instance.subscription.value?.paidUntil
            if (paidUntil.isNullOrBlank()) return

            val expiry = SubscriptionPolicy.parseExpiry(paidUntil) ?: run {
                AppLogger.w(TAG, "Не удалось разобрать дату окончания: $paidUntil")
                return
            }

            val remainingMs = expiry.time - System.currentTimeMillis()
            if (remainingMs <= 0L) return

            val days = TimeUnit.MILLISECONDS.toDays(remainingMs).toInt()
            val matched = SubscriptionPolicy.expiryReminderStep(days) ?: return

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val notifiedFor = prefs.getString(KEY_LAST_NOTIFIED_FOR, "") ?: ""
            val notifiedSteps = prefs.getStringSet(KEY_NOTIFIED_STEPS, emptySet()) ?: emptySet()

            if (notifiedFor == paidUntil && matched.toString() in notifiedSteps) return

            if (!canPost(context)) {
                AppLogger.d(TAG, "Уведомления не разрешены, пропуск")
                return
            }

            createChannel(context)
            post(context, days)

            prefs.edit()
                .putString(KEY_LAST_NOTIFIED_FOR, paidUntil)
                .putStringSet(KEY_NOTIFIED_STEPS, notifiedSteps + matched.toString())
                .apply()
            AppLogger.i(TAG, "Отправлено уведомление об окончании подписки: дней=$days порог=$matched")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Проверка окончания подписки не удалась: ${t.message}")
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
    private fun post(context: Context, daysRemaining: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SHOW_SUBSCRIPTION, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (daysRemaining) {
            1 -> context.getString(R.string.subscription_expiring_tomorrow_title)
            2 -> context.getString(R.string.subscription_expiring_two_days_title)
            3 -> context.getString(R.string.subscription_expiring_three_days_title)
            else -> context.getString(R.string.subscription_expiring_days_title, daysRemaining)
        }
        val body = context.getString(R.string.subscription_expiring_body, daysRemaining)

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
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Не удалось показать уведомление: ${t.message}")
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val name = context.getString(R.string.subscription_channel_name)
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(
                R.string.subscription_channel_description,
                name,
            )
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
