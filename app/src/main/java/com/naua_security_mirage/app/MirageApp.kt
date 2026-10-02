package com.naua_security_mirage.app

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.naua_security_mirage.app.data.repository.GeoRoutingRepository
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.util.AppLogger
import com.naua_security_mirage.app.work.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MirageApp : Application() {

    override fun onCreate() {
        super.onCreate()

        com.naua_security_mirage.app.util.AppShield.checkIntegrity(this)

        AppLogger.init(this)
        com.naua_security_mirage.app.data.supabase.SupabaseManager.instance.init(this)

        val settingsRepository = SettingsRepository(this)
        val telemetryEnabled = settingsRepository.isAnonymousTelemetryEnabled
        updateTelemetryState(this, telemetryEnabled)

        val geoRoutingRepository = GeoRoutingRepository(this)
        geoRoutingRepository.cleanupInvalidFiles()
        CoroutineScope(Dispatchers.IO).launch {
            geoRoutingRepository.autoUpdateIfNeeded()
        }
        scheduleUpdateCheck(this)
    }

    companion object {
        private const val TAG = "MirageApp"

        private const val UPDATE_WORK_NAME = "mirage_update_check"

        fun scheduleUpdateCheck(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UPDATE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Failed to schedule update check: ${t.message}")
            }
        }

        fun updateTelemetryState(context: Context, enabled: Boolean) {
            AppLogger.d(TAG, "Updating telemetry state: enabled=$enabled")
            AppLogger.system("Telemetry", "Сбор телеметрии Firebase: ${if (enabled) "ВКЛЮЧЕН (Analytics, Crashlytics, Performance)" else "ВЫКЛЮЧЕН (сбор данных остановлен)"}")

            try {
                FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Failed to set Analytics collection state: ${t.message}")
            }

            try {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Failed to set Crashlytics collection state: ${t.message}")
            }

            try {
                FirebasePerformance.getInstance().isPerformanceCollectionEnabled = enabled
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Failed to set Performance collection state: ${t.message}")
            }
        }
    }
}
