package com.naua_security_mirage.app.data.supabase

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.naua_security_mirage.app.data.config.PaidNode
import com.naua_security_mirage.app.util.AppLogger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.Postgrest
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
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

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

data class SupabaseUser(
    val id: String,
    val email: String
)

class SupabaseManager {
    companion object {
        private const val TAG = "SupabaseManager"
        val instance by lazy { SupabaseManager() }

        private const val PREF_ACCESS_TOKEN = "access_token"
        private const val PREF_REFRESH_TOKEN = "refresh_token"
        private const val PREF_USER_ID = "user_id"
        private const val PREF_USER_EMAIL = "user_email"

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

    private val _currentUser = MutableStateFlow<SupabaseUser?>(null)
    val currentUser: StateFlow<SupabaseUser?> = _currentUser.asStateFlow()

    private val _subscription = MutableStateFlow<SubscriptionDto?>(null)
    val subscription: StateFlow<SubscriptionDto?> = _subscription.asStateFlow()

    var accessToken: String? = null
        private set
    var refreshToken: String? = null
        private set

    init {
        try {
            val prefs = Preferences.userNodeForPackage(SupabaseManager::class.java)
            val savedToken = prefs.get(PREF_ACCESS_TOKEN, null)
            val savedRefresh = prefs.get(PREF_REFRESH_TOKEN, null)
            val savedId = prefs.get(PREF_USER_ID, null)
            val savedEmail = prefs.get(PREF_USER_EMAIL, null)

            if (!savedId.isNullOrBlank() && !savedToken.isNullOrBlank()) {
                accessToken = savedToken
                refreshToken = savedRefresh
                _currentUser.value = SupabaseUser(savedId, savedEmail.orEmpty())
                AppLogger.info(TAG, "Restored active session for $savedEmail ($savedId)")
                scope.launch {
                    refreshSubscription()
                }
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to restore session from preferences: ${e.message}")
        }
    }

    fun getActiveToken(): String? = accessToken

    private fun persistSession(token: String, refresh: String?, user: SupabaseUser) {
        accessToken = token
        if (refresh != null) refreshToken = refresh
        _currentUser.value = user
        try {
            val prefs = Preferences.userNodeForPackage(SupabaseManager::class.java)
            prefs.put(PREF_ACCESS_TOKEN, token)
            if (refresh != null) prefs.put(PREF_REFRESH_TOKEN, refresh)
            prefs.put(PREF_USER_ID, user.id)
            prefs.put(PREF_USER_EMAIL, user.email)
            prefs.flush()
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to persist session: ${e.message}")
        }
        scope.launch { refreshSubscription() }
    }

    suspend fun signInWithGoogle(): Result<Unit> {
        val url = SupabaseConfig.SUPABASE_URL
        if (url.isBlank()) {
            val msg = "Supabase URL не сконфигурирован. Проверьте mirage.properties или переменные окружения."
            AppLogger.error(TAG, msg)
            return Result.failure(IllegalStateException(msg))
        }
        return try {
            val redirectUri = java.net.URLEncoder.encode("mirage://auth/callback", "UTF-8")
            val oauthUrl = "${url.trimEnd('/')}/auth/v1/authorize?provider=google&redirect_to=$redirectUri"
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(oauthUrl))
            } else {
                Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", oauthUrl))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Google sign in failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun handleAuthCallbackUrl(urlOrToken: String): Result<SupabaseUser> = withContext(Dispatchers.IO) {
        try {
            val trimmed = urlOrToken.trim()
            var tokenToUse: String? = null
            var refreshTokenToUse: String? = null

            if (trimmed.startsWith("eyJ") && !trimmed.contains("=") && !trimmed.contains("&")) {
                tokenToUse = trimmed
            } else {
                val fragmentOrQuery = when {
                    trimmed.contains("#") -> trimmed.substringAfter("#")
                    trimmed.contains("?") -> trimmed.substringAfter("?")
                    else -> trimmed
                }
                val params = mutableMapOf<String, String>()
                fragmentOrQuery.split("&").forEach { part ->
                    val pair = part.split("=", limit = 2)
                    if (pair.isNotEmpty()) {
                        val k = pair[0]
                        val v = if (pair.size > 1) {
                            try { java.net.URLDecoder.decode(pair[1], "UTF-8") } catch (_: Exception) { pair[1] }
                        } else ""
                        params[k] = v
                    }
                }

                val err = params["error_description"] ?: params["error"]
                if (!err.isNullOrBlank()) {
                    return@withContext Result.failure(Exception(err))
                }

                tokenToUse = params["access_token"]
                refreshTokenToUse = params["refresh_token"]

                if (tokenToUse.isNullOrBlank() && !params["code"].isNullOrBlank()) {
                    // exchange PKCE code
                    val exchanged = exchangeCode(params["code"]!!)
                    if (exchanged.isSuccess) {
                        return@withContext exchanged
                    }
                }
            }

            if (tokenToUse.isNullOrBlank()) {
                return@withContext Result.failure(Exception("В предоставленной ссылке не найден токен доступа (access_token)"))
            }

            var userId = ""
            var email = ""

            // 1. Try to fetch profile from /auth/v1/user
            try {
                val reqBuilder = Request.Builder()
                    .url("${SupabaseConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/user")
                    .header("Authorization", "Bearer $tokenToUse")
                val anon = SupabaseConfig.getAnonKey()
                if (anon.isNotEmpty()) {
                    reqBuilder.header("apikey", anon)
                } else {
                    reqBuilder.header("apikey", tokenToUse)
                }
                val resp = httpClient.newCall(reqBuilder.get().build()).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val userObj = gson.fromJson(body, JsonObject::class.java)
                    userId = userObj?.get("id")?.asString.orEmpty()
                    email = userObj?.get("email")?.asString.orEmpty()
                }
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Failed to query /auth/v1/user: ${e.message}")
            }

            // 2. Fallback: Parse user ID and email directly from JWT payload
            if (userId.isEmpty() && tokenToUse.contains(".")) {
                try {
                    val parts = tokenToUse.split(".")
                    if (parts.size >= 2) {
                        val payloadBytes = java.util.Base64.getUrlDecoder().decode(parts[1])
                        val payloadJson = String(payloadBytes, Charsets.UTF_8)
                        val jwtObj = gson.fromJson(payloadJson, JsonObject::class.java)
                        userId = jwtObj.get("sub")?.asString.orEmpty()
                        email = jwtObj.get("email")?.asString.orEmpty()
                    }
                } catch (e: Exception) {
                    AppLogger.warn(TAG, "Failed to decode JWT payload: ${e.message}")
                }
            }

            if (userId.isEmpty()) {
                return@withContext Result.failure(Exception("Не удалось получить профиль пользователя"))
            }

            val user = SupabaseUser(userId, email)
            persistSession(tokenToUse, refreshTokenToUse, user)
            AppLogger.info(TAG, "OAuth user logged in: $email ($userId)")
            Result.success(user)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Error handling auth callback URL: ${e.message}")
            Result.failure(e)
        }
    }

    private fun exchangeCode(code: String): Result<SupabaseUser> {
        return try {
            val body = JsonObject().apply {
                addProperty("auth_code", code)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/token?grant_type=pkce")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", "Bearer ${SupabaseConfig.getAnonKey()}")
                .post(body)
                .build()

            val resp = httpClient.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return Result.failure(Exception("Code exchange failed: HTTP ${resp.code}"))
            }

            val resObj = gson.fromJson(text, JsonObject::class.java)
            val token = resObj.get("access_token")?.asString.orEmpty()
            val refresh = resObj.get("refresh_token")?.asString
            val userObj = resObj.getAsJsonObject("user")
            val userId = userObj?.get("id")?.asString.orEmpty()
            val email = userObj?.get("email")?.asString.orEmpty()

            if (token.isEmpty() || userId.isEmpty()) {
                return Result.failure(Exception("Empty token or user in code exchange response"))
            }

            val user = SupabaseUser(userId, email)
            persistSession(token, refresh, user)
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        try {
            accessToken = null
            refreshToken = null
            _currentUser.value = null
            _subscription.value = null
            dynamicKeysCache.clear()

            val prefs = Preferences.userNodeForPackage(SupabaseManager::class.java)
            prefs.remove(PREF_ACCESS_TOKEN)
            prefs.remove(PREF_REFRESH_TOKEN)
            prefs.remove(PREF_USER_ID)
            prefs.remove(PREF_USER_EMAIL)
            prefs.flush()

            try {
                client.auth.signOut()
            } catch (_: Exception) {}

            AppLogger.info(TAG, "User signed out")
        } catch (e: Exception) {
            AppLogger.error(TAG, "Sign out error: ${e.message}")
        }
    }

    suspend fun refreshSubscription() = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext
        try {
            val token = getActiveToken() ?: SupabaseConfig.getAnonKey()
            val encodedId = java.net.URLEncoder.encode(user.id, "UTF-8")
            val req = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/subscriptions?user_id=eq.$encodedId&select=user_id,email,is_active,plan,paid_until,vless_key,client_uuid,updated_at")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val resp = httpClient.newCall(req).execute()
            if (resp.code == 401 && refreshAccessToken()) {
                val refreshedToken = getActiveToken() ?: SupabaseConfig.getAnonKey()
                val retryReq = Request.Builder()
                    .url("${SupabaseConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/subscriptions?user_id=eq.$encodedId&select=user_id,email,is_active,plan,paid_until,vless_key,client_uuid,updated_at")
                    .header("apikey", SupabaseConfig.getAnonKey())
                    .header("Authorization", "Bearer $refreshedToken")
                    .get()
                    .build()
                val retryResp = httpClient.newCall(retryReq).execute()
                handleSubscriptionResponse(retryResp, user)
                return@withContext
            }

            handleSubscriptionResponse(resp, user)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to refresh subscription: ${e.message}")
        }
    }

    private fun handleSubscriptionResponse(resp: okhttp3.Response, user: SupabaseUser) {
        if (resp.isSuccessful) {
            val body = resp.body?.string().orEmpty()
            val list = gson.fromJson(body, JsonArray::class.java)
            if (list != null && list.size() > 0) {
                val subDto = gson.fromJson(list[0], SubscriptionDto::class.java)
                _subscription.value = subDto
                AppLogger.info(TAG, "Subscription loaded: active=${subDto.isActive}, until=${subDto.paidUntil}")
            } else {
                val defaultSub = SubscriptionDto(
                    userId = user.id,
                    email = user.email,
                    isActive = false,
                    plan = "free"
                )
                _subscription.value = defaultSub
                AppLogger.info(TAG, "No subscription row; using free state")
            }
        }
    }

    private fun refreshAccessToken(): Boolean {
        val token = refreshToken ?: return false
        return try {
            val body = JsonObject().apply { addProperty("refresh_token", token) }
                .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/token?grant_type=refresh_token")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", "Bearer ${SupabaseConfig.getAnonKey()}")
                .post(body)
                .build()

            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = gson.fromJson(resp.body?.string().orEmpty(), JsonObject::class.java)
                val newAccess = json.get("access_token")?.asString
                val newRefresh = json.get("refresh_token")?.asString
                if (!newAccess.isNullOrBlank()) {
                    accessToken = newAccess
                    if (!newRefresh.isNullOrBlank()) refreshToken = newRefresh
                    try {
                        val prefs = Preferences.userNodeForPackage(SupabaseManager::class.java)
                        prefs.put(PREF_ACCESS_TOKEN, newAccess)
                        if (!newRefresh.isNullOrBlank()) prefs.put(PREF_REFRESH_TOKEN, newRefresh)
                        prefs.flush()
                    } catch (_: Exception) {}
                    return true
                }
            }
            false
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Refresh token failed: ${e.message}")
            false
        }
    }

    suspend fun ensureFranceVlessKey(): String? {
        val sub = _subscription.value
        if (sub?.vlessKey != null && sub.vlessKey.isNotBlank()) {
            return sub.vlessKey
        }
        val user = _currentUser.value ?: return null
        val generatedKey = ThreeXUiService.getOrCreateClient(user.email, user.id)
        if (generatedKey != null) {
            val updated = (sub ?: SubscriptionDto(
                userId = user.id,
                email = user.email,
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

        val token = getActiveToken()
        if (token.isNullOrEmpty()) {
            AppLogger.warn(TAG, "Cannot fetch Belarus key: no active auth token")
            return@withContext null
        }

        try {
            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/belarus-config")
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
                val token = getActiveToken() ?: return null
                try {
                    val request = Request.Builder()
                        .url("${SupabaseConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/${node.configFunction}")
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
        val token = getActiveToken()
            ?: return@withContext Result.failure(Exception("Требуется вход в аккаунт"))
        try {
            val jsonBody = JsonObject().apply {
                addProperty("device_key", deviceId)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/free-trial")
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
