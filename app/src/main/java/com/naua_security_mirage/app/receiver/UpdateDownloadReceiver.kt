package com.naua_security_mirage.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.util.AppUpdateManager
import com.naua_security_mirage.app.work.UpdateNotifier

class UpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action == ACTION_CANCEL_DOWNLOAD) {
            AppLogger.d(TAG, "Update download cancel requested via notification")
            AppUpdateManager.cancelDownload(context)
            UpdateNotifier.cancelDownloadNotification(context)
        }
    }

    companion object {
        private const val TAG = "UpdateDownloadReceiver"
        const val ACTION_CANCEL_DOWNLOAD = "com.naua_security_mirage.app.CANCEL_UPDATE_DOWNLOAD"
    }
}
