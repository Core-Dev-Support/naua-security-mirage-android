package com.naua_security_mirage.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.data.supabase.SupabaseConfig
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
            val url = "${SupabaseConfig.SUPABASE_URL}/functions/v1/latest-release"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NAUA-Security-Mirage/${BuildConfig.VERSION_NAME}")
                .build()

            val payload = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "Release check responded ${response.code}")
                    return@withContext Result.retry()
                }
                response.body?.string()
            }

            if (payload.isNullOrEmpty()) return@withContext Result.success()

            val tag = JSONObject(payload).optString("tag_name", "")
            if (tag.isEmpty()) return@withContext Result.success()
            if (!AppUpdateManager.isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
                return@withContext Result.success()
            }

            UpdateNotifier.notifyIfNewer(applicationContext, tag)
            Result.success()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Background update check failed: ${t.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"

        private val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
