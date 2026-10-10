package com.naua_security_mirage.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.naua_security_mirage.app.util.AppLogger

class NodeStatusWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            NodeStatusAlertNotifier.check(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Проверка состояния узла не удалась: ${t.message}")
            Result.success()
        }
    }

    private companion object {
        const val TAG = "NodeStatusWorker"
    }
}
