package com.naua_security_mirage.app.data.config

import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.data.supabase.SupabaseConfig
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object AppConfigRepository {

    private const val TAG = "AppConfig"
    private const val CACHE_TTL_MS = 60 * 1000L

    private val scope = CoroutineScope(Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val cacheFile: File by lazy {
        val dataDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "NAUA Security Mirage").apply { mkdirs() }
        File(dataDir, "app_config.json")
    }

    private var settingsRepository: SettingsRepository? = null

    private val _config = MutableStateFlow(AppConfig.defaults())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    @Volatile private var fetching = false
    @Volatile private var lastFetchAt = 0L

    fun init(settings: SettingsRepository) {
        settingsRepository = settings
        loadCache()
        applySettings()
        refresh(force = true)
    }

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

    fun nodeLabel(id: String?, isEnglish: Boolean = false): String? = node(id)?.label(isEnglish)

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
                    AppLogger.w(TAG, "app-config вернул запасной конфиг, кэш сохранён")
                    return
                }
                val parsed = parseAppConfig(body, System.currentTimeMillis()) ?: return
                _config.value = parsed
                lastFetchAt = parsed.fetchedAt
                persistCache(body, parsed.fetchedAt)
                applySettings()
                AppLogger.i(TAG, "Конфиг обновлен: ${parsed.paidNodes.size} узлов, ${parsed.plans.size} тарифов")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка загрузки app-config: ${e.message}")
        }
    }

    fun applySettings() {
        val repo = settingsRepository ?: return
        val currentPlan = repo.selectedServerPlan
        if (repo.isPaidPlan(currentPlan) && node(currentPlan) == null) {
            val fallback = nodes().firstOrNull()?.id ?: SettingsRepository.PLAN_FREE
            AppLogger.i(TAG, "Узел '$currentPlan' отсутствует в конфиге, переключение на '$fallback'")
            repo.selectedServerPlan = fallback
        }
        val last = repo.lastPaidPlan
        if (node(last) == null) {
            nodes().firstOrNull()?.id?.let { repo.lastPaidPlan = it }
        }
    }

    private fun loadCache() {
        try {
            if (cacheFile.exists()) {
                val raw = cacheFile.readText()
                val parsed = parseAppConfig(raw, cacheFile.lastModified())
                if (parsed != null) {
                    _config.value = parsed
                    lastFetchAt = parsed.fetchedAt
                    AppLogger.d(TAG, "Загружен кэш конфигурации от ${cacheFile.lastModified()}")
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка чтения кэша конфигурации: ${e.message}")
        }
    }

    private fun persistCache(json: String, fetchedAt: Long) {
        try {
            cacheFile.writeText(json)
            cacheFile.setLastModified(fetchedAt)
        } catch (_: Exception) {}
    }
}
