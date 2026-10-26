package com.naua_security_mirage.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.vpn.BelarusNodeMonitor

class NodeStatusWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            NodeStatusAlertNotifier.check(applicationContext)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Проверка состояния Франции не удалась: ${t.message}")
        }
        try {
            BelarusNodeMonitor.checkTracked(applicationContext)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Проверка состояния Беларуси не удалась: ${t.message}")
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "NodeStatusWorker"
    }
}
