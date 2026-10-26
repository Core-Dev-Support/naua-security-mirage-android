package com.naua_security_mirage.app.data.config

import android.content.Context
import android.content.SharedPreferences
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.data.supabase.SupabaseConfig
import com.naua_security_mirage.app.data.supabase.forSupabase
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Loads prices and the paid-node catalogue from `app-config`.
 *
 * The last good response is cached in SharedPreferences, and the built-in
 * France/Belarus defaults are used only when no cache exists or the endpoint
 * is unreachable. A successfully fetched empty node list is respected: that is
 * how an operator removes the last paid button.
 */
object AppConfigRepository {

    private const val TAG = "AppConfig"
    private const val PREFS = "mirage_app_config"
    private const val KEY_JSON = "config_json"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val CACHE_TTL_MS = 60 * 1000L

    private val scope = CoroutineScope(Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .forSupabase()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val _config = MutableStateFlow(AppConfig.defaults())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    @Volatile private var fetching = false
    @Volatile private var lastFetchAt = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadCache()
        applySettings()
        refresh(force = true)
    }

    /** Called on app start and when the subscription dialog opens. */
    fun refresh(force: Boolean = false) {
        if (fetching) return
        if (!force && lastFetchAt > 0 && System.currentTimeMillis() - lastFetchAt < CACHE_TTL_MS) {
            return
        }
        fetching = true
        scope.launch {
            try {
                fetch()
            } finally {
                fetching = false
            }
        }
    }

    fun node(id: String?): PaidNode? = _config.value.node(id)

    fun nodes(): List<PaidNode> = _config.value.paidNodes

    fun nodeLabel(id: String?): String? = node(id)?.label()

    private fun fetch() {
        val url = "${SupabaseConfig.SUPABASE_URL}/functions/v1/app-config"
        val request = Request.Builder()
            .url(url)
            .header("apikey", SupabaseConfig.getAnonKey())
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "app-config недоступен: HTTP ${response.code}, используется кэш")
                    return
                }
                val body = response.body?.string().orEmpty()
                if (isAppConfigFallback(body) && lastFetchAt > 0L) {
                    // app-config is up but its database read failed. Keep the last
                    // good catalogue instead of replacing Finland with defaults.
                    AppLogger.w(TAG, "app-config вернул запасной конфиг, кэш сохранён")
                    return
                }
                val parsed = parseAppConfig(body, System.currentTimeMillis())
                if (parsed == null) {
                    AppLogger.w(TAG, "app-config вернул некорректный ответ, используется кэш")
                    return
                }
                _config.value = parsed
                lastFetchAt = parsed.fetchedAt
                prefs?.edit()
                    ?.putString(KEY_JSON, body)
                    ?.putLong(KEY_FETCHED_AT, parsed.fetchedAt)
                    ?.apply()
                applySettings()
                AppLogger.i(
                    TAG,
                    "Конфиг обновлён: тарифов ${parsed.plans.size}, платных узлов ${parsed.paidNodes.size}" +
                            (parsed.version?.let { ", версия $it" } ?: "")
                )
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "app-config: ${t.message}, используется кэш")
        }
    }

    private fun loadCache() {
        val raw = prefs?.getString(KEY_JSON, null)?.takeIf { it.isNotBlank() } ?: return
        val fetchedAt = prefs?.getLong(KEY_FETCHED_AT, 0L) ?: 0L
        try {
            val parsed = parseAppConfig(raw, fetchedAt)
            if (parsed != null) {
                _config.value = parsed
                lastFetchAt = fetchedAt
                AppLogger.i(
                    TAG,
                    "Конфиг из кэша: тарифов ${parsed.plans.size}, платных узлов ${parsed.paidNodes.size}"
                )
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Не удалось прочитать кэш app-config: ${t.message}")
        }
    }

    fun applySettings() {
        val context = appContext ?: return
        val settings = SettingsRepository(context)
        val nodes = _config.value.paidNodes
        val selected = settings.selectedServerPlan
        if (selected != SettingsRepository.PLAN_FREE && nodes.none { it.id == selected }) {
            settings.selectedServerPlan = SettingsRepository.PLAN_FREE
            AppLogger.w(TAG, "Выбранный платный узел '$selected' убран из конфига, включён бесплатный")
        }
        if (nodes.none { it.id == settings.lastPaidPlan }) {
            val fallback = nodes.firstOrNull()?.id ?: SettingsRepository.PLAN_PREMIUM_FRANCE
            settings.lastPaidPlan = fallback
            AppLogger.i(TAG, "Последний платный узел переключён на '$fallback'")
        }
    }
}
