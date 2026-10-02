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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.supabase.SupabaseConfig
import com.naua_security_mirage.app.ui.MainActivity
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.util.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val url = "${SupabaseConfig.SUPABASE_URL}/functions/v1/latest-release"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NAUA-Security-Mirage/${BuildConfig.VERSION_NAME}")
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "Release check responded ${response.code}")
                    return@withContext Result.retry()
                }
                response.body?.string()
            }

            if (body.isNullOrEmpty()) return@withContext Result.success()

            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            if (tag.isEmpty()) return@withContext Result.success()
            if (!AppUpdateManager.isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
                return@withContext Result.success()
            }
            if (prefs.getString(KEY_NOTIFIED_TAG, "") == tag) return@withContext Result.success()
            prefs.edit().putString(KEY_NOTIFIED_TAG, tag).apply()

            showNotification(tag, json.optString("name", tag))
            Result.success()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Background update check failed: ${t.message}")
            Result.retry()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showNotification(tag: String, releaseName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.d(TAG, "Notifications not granted, skipping $tag")
            return
        }

        createChannel()

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.update_available_title, releaseName))
            .setContentText(applicationContext.getString(R.string.update_available_body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(tag.hashCode(), notification)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to show notification: ${t.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = applicationContext.getString(R.string.update_channel_description)
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val CHANNEL_ID = "mirage_updates"
        private const val PREFS = "mirage_update_worker"
        private const val KEY_NOTIFIED_TAG = "notified_tag"

        private val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
