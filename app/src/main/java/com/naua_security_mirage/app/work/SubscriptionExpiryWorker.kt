package com.naua_security_mirage.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.naua_security_mirage.app.util.AppLogger

class SubscriptionExpiryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            SubscriptionExpiryNotifier.check(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Проверка подписки не удалась: ${t.message}")
            Result.success()
        }
    }

    private companion object {
        const val TAG = "SubscriptionExpiryWorker"
    }
}
