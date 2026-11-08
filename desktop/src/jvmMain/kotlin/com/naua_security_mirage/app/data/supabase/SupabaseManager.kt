package com.naua_security_mirage.app.data.supabase

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.naua_security_mirage.app.data.config.PaidNode
import com.naua_security_mirage.app.util.AppLogger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.user.UserInfo
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@Serializable
data class SubscriptionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("email") val email: String,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("plan") val plan: String = "free",
    @SerialName("paid_until") val paidUntil: String? = null,
    @SerialName("vless_key") val vlessKey: String? = null,
    @SerialName("belarus_vless_key") val belarusVlessKey: String? = null
)

class SupabaseManager {
    companion object {
        private const val TAG = "SupabaseManager"
        val instance by lazy { SupabaseManager() }

        fun maskEmail(email: String): String {
            val at = email.indexOf('@')
            if (at <= 1) return "***"
            return "${email[0]}***${email.substring(at)}"
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val dynamicKeysCache = mutableMapOf<String, String>()

    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = SupabaseConfig.SUPABASE_URL.ifBlank { "https://placeholder.supabase.co" },
        supabaseKey = SupabaseConfig.getAnonKey().ifBlank { "placeholder" }
    ) {
        install(Auth)
        install(Postgrest)
    }

    private val _currentUser = MutableStateFlow<UserInfo?>(null)
    val currentUser: StateFlow<UserInfo?> = _currentUser.asStateFlow()

    private val _subscription = MutableStateFlow<SubscriptionDto?>(null)
    val subscription: StateFlow<SubscriptionDto?> = _subscription.asStateFlow()

    init {
        scope.launch {
            try {
                val current = client.auth.currentUserOrNull()
                _currentUser.value = current
                if (current != null) {
                    refreshSubscription()
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to load initial auth state: ${e.message}")
            }
        }
    }

    suspend fun signInWithGoogle(): Result<Unit> {
        val url = SupabaseConfig.SUPABASE_URL
        if (url.isBlank()) {
            val msg = "Supabase URL не сконфигурирован. Проверьте mirage.properties или переменные окружения."
            AppLogger.error(TAG, msg)
            return Result.failure(IllegalStateException(msg))
        }
        return try {
            try {
                client.auth.signInWith(Google)
            } catch (innerEx: Exception) {
                AppLogger.warn(TAG, "Direct Google SDK sign in error (${innerEx.message}), opening browser OAuth...")
                val oauthUrl = "${url.trimEnd('/')}/auth/v1/authorize?provider=google&redirect_to=http://localhost:8000/callback"
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI(oauthUrl))
                } else {
                    Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", oauthUrl))
                }
            }
            val user = client.auth.currentUserOrNull()
            _currentUser.value = user
            if (user != null) {
                refreshSubscription()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Google sign in failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        try {
            client.auth.signOut()
            _currentUser.value = null
            _subscription.value = null
            dynamicKeysCache.clear()
            AppLogger.info(TAG, "User signed out")
        } catch (e: Exception) {
            AppLogger.error(TAG, "Sign out error: ${e.message}")
        }
    }

    suspend fun refreshSubscription() {
        val user = _currentUser.value ?: client.auth.currentUserOrNull() ?: return
        try {
            val res = client.from("subscriptions")
                .select {
                    filter {
                        eq("user_id", user.id)
                    }
                }
                .decodeSingleOrNull<SubscriptionDto>()

            if (res != null) {
                _subscription.value = res
                AppLogger.info(TAG, "Subscription loaded: active=${res.isActive}, until=${res.paidUntil}")
            } else {
                val defaultSub = SubscriptionDto(
                    userId = user.id,
                    email = user.email.orEmpty(),
                    isActive = false,
                    plan = "free"
                )
                _subscription.value = defaultSub
                AppLogger.info(TAG, "No subscription row; using free state")
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to refresh subscription: ${e.message}")
        }
    }

    suspend fun ensureFranceVlessKey(): String? {
        val sub = _subscription.value
        if (sub?.vlessKey != null && sub.vlessKey.isNotBlank()) {
            return sub.vlessKey
        }
        val user = _currentUser.value ?: return null
        val generatedKey = ThreeXUiService.getOrCreateClient(user.email.orEmpty(), user.id)
        if (generatedKey != null) {
            val updated = (sub ?: SubscriptionDto(
                userId = user.id,
                email = user.email.orEmpty(),
                isActive = true,
                plan = "premium"
            )).copy(vlessKey = generatedKey, isActive = true)
            _subscription.value = updated
        }
        return generatedKey
    }

    suspend fun ensureBelarusVlessKey(): String? = withContext(Dispatchers.IO) {
        val sub = _subscription.value
        if (sub?.belarusVlessKey != null && sub.belarusVlessKey.isNotBlank()) {
            return@withContext sub.belarusVlessKey
        }
        val cached = dynamicKeysCache["premium_belarus"]
        if (!cached.isNullOrBlank()) return@withContext cached

        val token = client.auth.currentAccessTokenOrNull()
        if (token.isNullOrEmpty()) {
            AppLogger.warn(TAG, "Cannot fetch Belarus key: no active auth token")
            return@withContext null
        }

        try {
            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL}/functions/v1/belarus-config")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.warn(TAG, "belarus-config HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string().orEmpty()
                val json = gson.fromJson(body, JsonObject::class.java)
                val key = json?.get("vlessKey")?.asString
                if (!key.isNullOrBlank()) {
                    dynamicKeysCache["premium_belarus"] = key
                    _subscription.value = (_subscription.value ?: SubscriptionDto(
                        userId = _currentUser.value?.id.orEmpty(),
                        email = _currentUser.value?.email.orEmpty(),
                        isActive = true,
                        plan = "premium"
                    )).copy(belarusVlessKey = key, isActive = true)
                    AppLogger.info(TAG, "Belarus key issued successfully")
                    return@withContext key
                }
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Error fetching Belarus key: ${e.message}")
        }
        return@withContext null
    }

    suspend fun ensurePaidNodeKey(node: PaidNode): String? {
        return when (node.kind) {
            PaidNode.Kind.FRANCE -> ensureFranceVlessKey()
            PaidNode.Kind.BELARUS -> ensureBelarusVlessKey()
            PaidNode.Kind.VLESS -> {
                val cached = dynamicKeysCache[node.id]
                if (!cached.isNullOrBlank()) return cached
                val token = client.auth.currentAccessTokenOrNull() ?: return null
                try {
                    val request = Request.Builder()
                        .url("${SupabaseConfig.SUPABASE_URL}/functions/v1/${node.configFunction}")
                        .header("apikey", SupabaseConfig.getAnonKey())
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val key = json?.get("vlessKey")?.asString
                            if (!key.isNullOrBlank()) {
                                dynamicKeysCache[node.id] = key
                                return key
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.warn(TAG, "Failed to fetch key for ${node.id}: ${e.message}")
                }
                null
            }
        }
    }

    suspend fun requestFreeTrial(deviceId: String): Result<String> = withContext(Dispatchers.IO) {
        val token = client.auth.currentAccessTokenOrNull()
            ?: return@withContext Result.failure(Exception("Требуется вход в аккаунт"))
        try {
            val jsonBody = JsonObject().apply {
                addProperty("device_key", deviceId)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL}/functions/v1/free-trial")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", "Bearer $token")
                .post(jsonBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 409) {
                    return@withContext Result.failure(Exception("Пробный день уже использован"))
                }
                if (!response.isSuccessful) {
                    val err = try {
                        gson.fromJson(body, JsonObject::class.java)?.get("error")?.asString
                    } catch (_: Exception) { null } ?: "HTTP ${response.code}"
                    return@withContext Result.failure(Exception(err))
                }
                val resObj = gson.fromJson(body, JsonObject::class.java)
                val paidUntil = resObj?.get("paidUntil")?.asString.orEmpty()
                refreshSubscription()
                return@withContext Result.success(paidUntil)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun hasActiveSubscription(): Boolean {
        val sub = _subscription.value ?: return false
        if (!sub.isActive) return false
        val paidUntilStr = sub.paidUntil ?: return true
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val date = sdf.parse(paidUntilStr.take(19)) ?: return true
            date.after(Date())
        } catch (_: Exception) {
            true
        }
    }
}
