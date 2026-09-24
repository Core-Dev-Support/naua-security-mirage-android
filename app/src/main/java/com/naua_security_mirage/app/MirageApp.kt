package com.naua_security_mirage.app

import android.app.Application
import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.naua_security_mirage.app.data.repository.GeoRoutingRepository
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MirageApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Антидебаггинг и антитамперинг (проверка подписи и окружения)
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
    }

    companion object {
        private const val TAG = "MirageApp"

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
