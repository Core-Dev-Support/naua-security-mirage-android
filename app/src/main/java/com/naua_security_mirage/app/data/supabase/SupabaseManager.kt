package com.naua_security_mirage.app.data.supabase

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.*
import java.util.concurrent.TimeUnit

data class SupabaseUser(
    val id: String,
    val email: String
)

data class SubscriptionDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("user_id") val userId: String,
    @SerializedName("email") val email: String,
    @SerializedName("is_active") val isActive: Boolean = false,
    @SerializedName("plan") val plan: String = "free",
    @SerializedName("paid_until") val paidUntil: String? = null,
    @SerializedName("vless_key") val vlessKey: String? = null,
    @SerializedName("client_uuid") val clientUuid: String? = null,
    @SerializedName("flow") val flow: String? = null
)

class SupabaseManager private constructor() {

    companion object {
        private const val TAG = "SupabaseManager"

        private const val PREFS_NAME = "mirage_supabase_auth_sec"

        private const val PREFS_NAME_LEGACY = "mirage_supabase_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_SUB_USER_ID = "sub_user_id"
        private const val KEY_SUB_EMAIL = "sub_email"
        private const val KEY_SUB_IS_ACTIVE = "sub_is_active"
        private const val KEY_SUB_PLAN = "sub_plan"
        private const val KEY_SUB_PAID_UNTIL = "sub_paid_until"
        private const val KEY_SUB_VLESS_KEY = "sub_vless_key"
        private const val KEY_SUB_CLIENT_UUID = "sub_client_uuid"
        private const val KEY_SUB_FLOW = "sub_flow"

        private const val KEY_OAUTH_STATE = "oauth_state_pending"

        private const val KEY_OAUTH_STATE_AT = "oauth_state_pending_at"

        private const val KEY_OAUTH_VERIFIER = "oauth_code_verifier"

        private const val KEY_SUB_CONFIRMED_AT = "sub_confirmed_at"

        private const val CACHE_FRESH_MS = 10 * 60 * 1000L

        private const val OAUTH_STATE_TTL_MS = 10 * 60 * 1000L

        private const val PKCE_VERIFIER_BYTES = 64

        val instance by lazy { SupabaseManager() }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var prefs: SharedPreferences? = null
    private var accessToken: String? = null
    private var refreshToken: String? = null

    private val _currentUser = MutableStateFlow<SupabaseUser?>(null)
    val currentUser: StateFlow<SupabaseUser?> = _currentUser.asStateFlow()

    private val _subscription = MutableStateFlow<SubscriptionDto?>(null)
    val subscription: StateFlow<SubscriptionDto?> = _subscription.asStateFlow()

    private fun clearSubscriptionPrefs() {
        prefs?.edit()
            ?.remove(KEY_SUB_USER_ID)
            ?.remove(KEY_SUB_EMAIL)
            ?.remove(KEY_SUB_IS_ACTIVE)
            ?.remove(KEY_SUB_PLAN)
            ?.remove(KEY_SUB_PAID_UNTIL)
            ?.remove(KEY_SUB_VLESS_KEY)
            ?.remove(KEY_SUB_CLIENT_UUID)
            ?.remove(KEY_SUB_FLOW)
              ?.remove(KEY_SUB_CONFIRMED_AT)
            ?.apply()
    }

    private fun emailCacheKey(email: String): String {
        return "email_sub_${email.trim().lowercase()}"
    }

    private fun saveEmailCache(email: String, sub: SubscriptionDto) {
        try {
            prefs?.edit()?.putString(emailCacheKey(email), gson.toJson(sub))?.apply()
            AppLogger.info(TAG, "Email cache saved for $email: plan=${sub.plan}, uuid=${sub.clientUuid}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to save email cache: ${e.message}")
        }
    }

    private fun loadEmailCache(email: String): SubscriptionDto? {
        return try {
            val json = prefs?.getString(emailCacheKey(email), null) ?: return null
            val dto = gson.fromJson(json, SubscriptionDto::class.java)
            if (dto != null && SubscriptionPolicy.isActive(dto.isActive, dto.paidUntil)) {
                AppLogger.info(TAG, "Email cache loaded for $email: plan=${dto.plan}, uuid=${dto.clientUuid}")
                dto
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to load email cache: ${e.message}")
            null
        }
    }

    private fun clearEmailCache(email: String) {
        prefs?.edit()?.remove(emailCacheKey(email))?.apply()
        AppLogger.info(TAG, "Email cache cleared for $email")
    }

    private fun openAuthPrefs(context: Context): SharedPreferences {
        val plain = context.getSharedPreferences(PREFS_NAME_LEGACY, Context.MODE_PRIVATE)

        val encrypted = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {

            AppLogger.w(TAG, "Зашифрованное хранилище недоступно, сессия остаётся без защиты: ${e.message}")
            return plain
        }

        var migrated = 0
        for (key in plain.all.keys) {
            val value = plain.getString(key, null) ?: continue
            if (encrypted.getString(key, null) == null) {
                encrypted.edit().putString(key, value).commit()
            }
            plain.edit().remove(key).apply()
            migrated++
        }
        if (migrated > 0) {
            plain.edit().clear().commit()
            AppLogger.i(TAG, "Сессия перенесена в зашифрованное хранилище")
        }
        return encrypted
    }

    fun init(context: Context) {
        if (prefs == null) {
            prefs = openAuthPrefs(context)
            val savedToken = prefs?.getString(KEY_ACCESS_TOKEN, null)
            val savedRefreshToken = prefs?.getString(KEY_REFRESH_TOKEN, null)
            val savedId = prefs?.getString(KEY_USER_ID, null)
            val savedEmail = prefs?.getString(KEY_USER_EMAIL, null)

            if (!savedToken.isNullOrEmpty() && !savedId.isNullOrEmpty() && !savedEmail.isNullOrEmpty()) {
                accessToken = savedToken
                refreshToken = savedRefreshToken
                _currentUser.value = SupabaseUser(savedId, savedEmail)

                val cached = loadEmailCache(savedEmail)
                if (cached != null) {
                    _subscription.value = cached
                    AppLogger.info(TAG, "Init: subscription loaded from email cache for $savedEmail")
                } else {

                    val savedSubActive = prefs?.getBoolean(KEY_SUB_IS_ACTIVE, false) ?: false
                    val savedSubEmail = prefs?.getString(KEY_SUB_EMAIL, null)
                    val isSameUser = savedSubEmail != null && savedSubEmail.equals(savedEmail, ignoreCase = true)
                    if (savedSubActive && isSameUser && SubscriptionPolicy.isActive(true, prefs?.getString(KEY_SUB_PAID_UNTIL, null))) {
                        val sub = SubscriptionDto(
                            userId = savedId,
                            email = savedEmail,
                            isActive = true,
                            plan = prefs?.getString(KEY_SUB_PLAN, "premium") ?: "premium",
                            paidUntil = prefs?.getString(KEY_SUB_PAID_UNTIL, null),
                            vlessKey = prefs?.getString(KEY_SUB_VLESS_KEY, null),
                            clientUuid = prefs?.getString(KEY_SUB_CLIENT_UUID, null) ?: extractUuidFromVless(prefs?.getString(KEY_SUB_VLESS_KEY, null)),
                            flow = prefs?.getString(KEY_SUB_FLOW, null)
                        )
                        _subscription.value = sub

                        saveEmailCache(savedEmail, sub)
                        AppLogger.info(TAG, "Init: migrated old-style prefs to email cache for $savedEmail")
                    }
                }

                scope.launch {
                    refreshSubscription()
                }
            } else {
                _currentUser.value = null
                _subscription.value = null
            }
        }
    }

    suspend fun signInWithEmail(emailInput: String, passwordInput: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = JsonObject().apply {
                addProperty("email", emailInput.trim())
                addProperty("password", passwordInput)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL}/auth/v1/token?grant_type=password")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", "Bearer ${SupabaseConfig.getAnonKey()}")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseString = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                AppLogger.error(TAG, "Sign in failed: $responseString")
                val errMsg = try {
                    val errObj = gson.fromJson(responseString, JsonObject::class.java)
                    errObj.get("msg")?.asString
                        ?: errObj.get("message")?.asString
                        ?: errObj.get("error_description")?.asString
                        ?: "Ошибка ${response.code}"
                } catch (_: Exception) {
                    "Ошибка ${response.code}"
                }
                return@withContext Result.failure(Exception(errMsg))
            }

            val resObj = gson.fromJson(responseString, JsonObject::class.java)
            val token = resObj.get("access_token")?.asString
            val newRefreshToken = resObj.get("refresh_token")?.asString
            val userObj = resObj.getAsJsonObject("user")
            val userId = userObj?.get("id")?.asString.orEmpty()
            val userEmail = userObj?.get("email")?.asString.orEmpty()

            accessToken = token
            refreshToken = newRefreshToken
            val user = SupabaseUser(userId, userEmail)
            _currentUser.value = user

            _subscription.value = loadEmailCache(userEmail)

            prefs?.edit()
                ?.putString(KEY_ACCESS_TOKEN, token)
                ?.putString(KEY_REFRESH_TOKEN, newRefreshToken)
                ?.putString(KEY_USER_ID, userId)
                ?.putString(KEY_USER_EMAIL, userEmail)
                ?.apply()

            refreshSubscription()
            AppLogger.info(TAG, "User signed in: $userEmail (cached sub: ${_subscription.value?.plan ?: "none"})")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Sign in exception: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun signUpWithEmail(emailInput: String, passwordInput: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = JsonObject().apply {
                addProperty("email", emailInput.trim())
                addProperty("password", passwordInput)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL}/auth/v1/signup")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", "Bearer ${SupabaseConfig.getAnonKey()}")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseString = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                AppLogger.error(TAG, "Sign up failed: $responseString")
                val errMsg = try {
                    val errObj = gson.fromJson(responseString, JsonObject::class.java)
                    errObj.get("msg")?.asString
                        ?: errObj.get("message")?.asString
                        ?: errObj.get("error_description")?.asString
                        ?: "Ошибка ${response.code}"
                } catch (_: Exception) {
                    "Ошибка ${response.code}"
                }
                return@withContext Result.failure(Exception(errMsg))
            }

            val resObj = gson.fromJson(responseString, JsonObject::class.java)
            val token = resObj.get("access_token")?.asString
            val newRefreshToken = resObj.get("refresh_token")?.asString
            val userObj = resObj.getAsJsonObject("user")
            val userId = userObj?.get("id")?.asString.orEmpty()
            val userEmail = userObj?.get("email")?.asString.orEmpty()

            if (!token.isNullOrEmpty()) {
                accessToken = token
                refreshToken = newRefreshToken
                val user = SupabaseUser(userId, userEmail)
                _currentUser.value = user

                _subscription.value = loadEmailCache(userEmail)

                prefs?.edit()
                    ?.putString(KEY_ACCESS_TOKEN, token)
                    ?.putString(KEY_REFRESH_TOKEN, newRefreshToken)
                    ?.putString(KEY_USER_ID, userId)
                    ?.putString(KEY_USER_EMAIL, userEmail)
                    ?.apply()

                refreshSubscription()
            }

            AppLogger.info(TAG, "User signed up: $userEmail")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Sign up exception: ${e.message}")
            Result.failure(e)
        }
    }

    fun signInWithGoogle(context: Context) {
        try {
            val redirectUri = Uri.encode("mirage://auth/callback")
            val state = randomUrlSafeToken(24)
            val verifier = randomPkceVerifier()
            val challenge = pkceChallengeS256(verifier)
            AppLogger.registerSecrets(verifier, challenge, state)

            prefs?.edit()
                ?.putString(KEY_OAUTH_STATE, state)
                ?.putLong(KEY_OAUTH_STATE_AT, System.currentTimeMillis())
                ?.putString(KEY_OAUTH_VERIFIER, verifier)
                ?.commit()

            val oauthUrl = "${SupabaseConfig.SUPABASE_URL}/auth/v1/authorize?provider=google" +
                "&redirect_to=$redirectUri&state=$state" +
                "&flow_type=pkce&code_challenge=$challenge&code_challenge_method=S256"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(oauthUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {

            AppLogger.error(TAG, "Google sign in error: ${e.javaClass.simpleName}: ${e.message}")
            AppLogger.onUserMessage("Не удалось открыть вход через Google")
        }
    }

    private fun randomUrlSafeToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(buf)
        return android.util.Base64.encodeToString(
            buf,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    private fun randomPkceVerifier(): String {
        val buf = ByteArray(PKCE_VERIFIER_BYTES)
        java.security.SecureRandom().nextBytes(buf)
        return PkceVerifier.fromBytes(buf)
    }

    private fun pkceChallengeS256(verifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(
            digest,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    private fun exchangePkceCode(code: String, verifier: String): Pair<String, SupabaseUser>? {
        return try {
            val body = JsonObject().apply {
                addProperty("auth_code", code)
                addProperty("code_verifier", verifier)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val anon = SupabaseConfig.getAnonKey()
            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL}/auth/v1/token?grant_type=pkce")
                .header("apikey", anon)

                .header("Authorization", "Bearer $anon")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {

                    AppLogger.w(TAG, "Обмен кода отклонён: HTTP ${response.code} ${reasonFrom(text)}")
                    return null
                }
                val json = gson.fromJson(text, JsonObject::class.java)
                val token = json?.get("access_token")?.asString
                if (token.isNullOrEmpty()) {
                    AppLogger.w(TAG, "Обмен кода: в ответе нет токена ${reasonFrom(text)}")
                    return null
                }
                val userObj = json.getAsJsonObject("user")
                val uid = userObj?.get("id")?.asString.orEmpty()
                val email = userObj?.get("email")?.asString.orEmpty()
                if (uid.isEmpty()) {
                    AppLogger.w(TAG, "Обмен кода: в ответе нет пользователя")
                    return null
                }
                json.get("refresh_token")?.asString?.let { refreshToken = it }
                token to SupabaseUser(uid, email)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Обмен кода не выполнен: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun reasonFrom(text: String): String {
        return try {
            val json = gson.fromJson(text, JsonObject::class.java)
            (json?.get("error_description")?.asString
                ?: json?.get("msg")?.asString
                ?: json?.get("error")?.asString
                ?: "").take(160)
        } catch (_: Throwable) {
            ""
        }
    }

    suspend fun handleOAuthCallback(uri: Uri): Result<SupabaseUser> = withContext(Dispatchers.IO) {
        try {
            val params = mutableMapOf<String, String>()

            uri.fragment?.split("&")?.forEach { part ->
                val pair = part.split("=")
                if (pair.size >= 2) {
                    params[pair[0]] = Uri.decode(pair.subList(1, pair.size).joinToString("="))
                }
            }
            uri.queryParameterNames.forEach { key ->
                uri.getQueryParameter(key)?.let { params[key] = it }
            }

            val expectedState = prefs?.getString(KEY_OAUTH_STATE, null)
            val issuedAt = prefs?.getLong(KEY_OAUTH_STATE_AT, 0L) ?: 0L
            val verifier = prefs?.getString(KEY_OAUTH_VERIFIER, null)
            prefs?.edit()
                ?.remove(KEY_OAUTH_STATE)
                ?.remove(KEY_OAUTH_STATE_AT)
                ?.remove(KEY_OAUTH_VERIFIER)
                ?.commit()

            fun reject(reason: String): Result<SupabaseUser> {
                AppLogger.w(TAG, "OAuth отклонён: $reason")
                return Result.failure(
                    Exception("Ответ авторизации не соответствует начатому входу. Попробуйте войти заново.")
                )
            }

            val verdict = OAuthCallbackPolicy.evaluate(
                expectedState = expectedState,
                issuedAt = issuedAt,
                now = System.currentTimeMillis(),
                returnedState = params["state"],
                code = params["code"],
                verifier = verifier,
                stateTtlMs = OAUTH_STATE_TTL_MS
            )
            if (verdict is OAuthCallbackPolicy.Verdict.Reject) return@withContext reject(verdict.reason)

            val error = params["error_description"] ?: params["error"]
            if (!error.isNullOrBlank()) return@withContext Result.failure(Exception(error))

            val accepted = verdict as OAuthCallbackPolicy.Verdict.Accept
            val (token, user) = exchangePkceCode(params["code"]!!, accepted.codeVerifier)
                ?: return@withContext Result.failure(Exception("Не удалось обменять код авторизации на сессию."))

            accessToken = token
            _currentUser.value = user
            _subscription.value = loadEmailCache(user.email)

            prefs?.edit()
                ?.putString(KEY_ACCESS_TOKEN, token)
                ?.putString(KEY_REFRESH_TOKEN, refreshToken)
                ?.putString(KEY_USER_ID, user.id)
                ?.putString(KEY_USER_EMAIL, user.email)
                ?.apply()

            refreshSubscription()
            AppLogger.info(TAG, "OAuth user logged in: ${user.email} (cached sub: ${_subscription.value?.plan ?: "none"})")
            Result.success(user)
        } catch (e: Exception) {
            AppLogger.error(TAG, "OAuth callback error: ${e.message}")
            Result.failure(e)
        }
    }

    private fun refreshAccessToken(): Boolean {
        val token = refreshToken ?: return false
        return try {
            val body = JsonObject().apply { addProperty("refresh_token", token) }
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", "Bearer ${SupabaseConfig.getAnonKey()}")
                .post(body)
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return false
            val json = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
            val newAccess = json.get("access_token")?.asString ?: return false
            accessToken = newAccess
            json.get("refresh_token")?.asString?.let { newRefresh ->
                refreshToken = newRefresh
                prefs?.edit()?.putString(KEY_REFRESH_TOKEN, newRefresh)?.apply()
            }
            prefs?.edit()?.putString(KEY_ACCESS_TOKEN, newAccess)?.apply()
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Access token refresh failed: ${e.message}")
            false
        }
    }

    fun signOut() {
        accessToken = null
        refreshToken = null
        _currentUser.value = null
        _subscription.value = null
        prefs?.edit()
            ?.remove(KEY_ACCESS_TOKEN)
            ?.remove(KEY_REFRESH_TOKEN)
            ?.remove(KEY_USER_ID)
            ?.remove(KEY_USER_EMAIL)
            ?.apply()
        clearSubscriptionPrefs()

        AppLogger.info(TAG, "User signed out (email subscription caches preserved)")
    }

    suspend fun activatePremiumSubscription(): Boolean = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext false
        try {
            val vlessKey = fetchFranceConfigFromServer()
                ?: run {
                    AppLogger.w(TAG, "Активация не удалась: сервер ещё не выдал France-ключ")
                    return@withContext false
                }
            val expiryMs = System.currentTimeMillis() + (30L * 24L * 60L * 60L * 1000L)
            val paidUntil = SubscriptionPolicy.formatUtcIso(Date(expiryMs))

            val clientUuid = extractUuidFromVless(vlessKey) ?: user.id

            val sub = SubscriptionDto(
                userId = user.id,
                email = user.email,
                isActive = true,
                plan = "premium",
                paidUntil = paidUntil,
                vlessKey = vlessKey,
                clientUuid = clientUuid
            )

            _subscription.value = sub
            saveEmailCache(user.email, sub)

            prefs?.edit()
                ?.putString(KEY_SUB_USER_ID, user.id)
                ?.putString(KEY_SUB_EMAIL, user.email)
                ?.putBoolean(KEY_SUB_IS_ACTIVE, true)
                ?.putString(KEY_SUB_PLAN, "premium")
                ?.putString(KEY_SUB_PAID_UNTIL, paidUntil)
                ?.putString(KEY_SUB_VLESS_KEY, vlessKey)
                ?.putString(KEY_SUB_CLIENT_UUID, clientUuid)
                ?.apply()

            AppLogger.w(TAG, "Client-side subscription sync is disabled; webhook provisioning is required")

            AppLogger.info(TAG, "Premium subscription activated locally for ${user.email}, clientUuid=$clientUuid")
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to activate premium subscription: ${e.message}")
            false
        }
    }

    private suspend fun fetchFranceConfigFromServer(): String? = withContext(Dispatchers.IO) {
        when (val result = fetchFranceConfig()) {
            is FranceConfigResult.Key -> result.vlessKey
            is FranceConfigResult.NoSubscription -> {
                AppLogger.i(TAG, "Сервер сообщил: активной подписки нет")
                null
            }
            is FranceConfigResult.Unavailable -> {
                AppLogger.w(TAG, "france-config недоступен: ${result.reason}")
                null
            }
        }
    }

    private sealed class FranceConfigResult {
        data class Key(val vlessKey: String, val clientUuid: String, val paidUntil: String?) : FranceConfigResult()
        object NoSubscription : FranceConfigResult()
        data class Unavailable(val reason: String) : FranceConfigResult()
    }

    private suspend fun fetchFranceConfig(): FranceConfigResult = withContext(Dispatchers.IO) {
        try {
            val token = accessToken
            if (token.isNullOrEmpty()) {
                return@withContext FranceConfigResult.Unavailable("нет сессии")
            }
            fun call(bearer: String): Response {
                val request = Request.Builder()
                    .url("${SupabaseConfig.SUPABASE_URL}/functions/v1/france-config")
                    .header("apikey", SupabaseConfig.getAnonKey())
                    .header("Authorization", "Bearer $bearer")
                    .get()
                    .build()
                return httpClient.newCall(request).execute()
            }

            var response = call(token)
            if (response.code == 401 && refreshAccessToken()) {
                val refreshed = accessToken
                if (refreshed.isNullOrEmpty()) {
                    return@withContext FranceConfigResult.Unavailable("токен не обновился")
                }
                response = call(refreshed)
            }

            val body = response.body?.string().orEmpty()
            when {
                response.code == 403 -> FranceConfigResult.NoSubscription
                !response.isSuccessful -> FranceConfigResult.Unavailable("HTTP ${response.code}")
                else -> {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val key = json?.get("vlessKey")?.asString
                    if (key.isNullOrBlank()) {
                        FranceConfigResult.Unavailable("в ответе нет ключа")
                    } else {
                        FranceConfigResult.Key(
                            vlessKey = key,
                            clientUuid = json.get("clientUuid")?.asString.orEmpty(),
                            paidUntil = json.get("paidUntil")?.takeIf { !it.isJsonNull }?.asString
                        )
                    }
                }
            }
        } catch (e: Exception) {
            FranceConfigResult.Unavailable(e.message ?: "сетевая ошибка")
        }
    }

    private fun fetchSubscriptionResponse(userId: String): Pair<Int, String> {
        fun execute(): Pair<Int, String> {
            val authHeader = if (!accessToken.isNullOrEmpty()) "Bearer $accessToken" else "Bearer ${SupabaseConfig.getAnonKey()}"
            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL}/rest/v1/subscriptions?user_id=eq.${userId}&select=user_id,email,is_active,plan,paid_until,vless_key,client_uuid,updated_at")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", authHeader)
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            return response.code to response.body?.string().orEmpty()
        }

        val first = execute()
        return if (first.first == 401 && refreshAccessToken()) execute() else first
    }

    private fun fetchOptionalFlow(userId: String): String? {
        return try {
            val authHeader = if (!accessToken.isNullOrEmpty()) "Bearer $accessToken" else "Bearer ${SupabaseConfig.getAnonKey()}"
            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL}/rest/v1/subscriptions?user_id=eq.${userId}&select=flow&limit=1")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", authHeader)
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val rows = gson.fromJson(response.body?.string().orEmpty(), JsonArray::class.java)
            val first = if (rows != null && rows.size() > 0) rows.get(0) else null
            first?.asJsonObject?.get("flow")?.asString
        } catch (_: Exception) {

            null
        }
    }

    suspend fun refreshSubscription() = refreshMutex.withLock {
        refreshSubscriptionUnsafe()
    }

    private suspend fun refreshSubscriptionUnsafe() = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: run {
            _subscription.value = null
            return@withContext
        }

        try {

            var serverReached = false
            var subFrom3XUi: SubscriptionDto? = null
            val cachedSub = _subscription.value ?: loadEmailCache(user.email)
            when (val cfg = fetchFranceConfig()) {
                is FranceConfigResult.Key -> {
                    serverReached = true
                    val clientUuid = cfg.clientUuid.ifBlank {
                        extractUuidFromVless(cfg.vlessKey) ?: cachedSub?.clientUuid.orEmpty()
                    }
                    subFrom3XUi = SubscriptionDto(
                        userId = user.id,
                        email = user.email,
                        isActive = true,
                        plan = "premium",
                        paidUntil = cfg.paidUntil ?: cachedSub?.paidUntil,
                        vlessKey = cfg.vlessKey,
                        clientUuid = clientUuid
                    )
                }
                is FranceConfigResult.NoSubscription -> serverReached = true
                is FranceConfigResult.Unavailable ->
                    AppLogger.w(TAG, "Сервер недоступен при обновлении: ${cfg.reason}")
            }

            if (subFrom3XUi != null) {

                val realClientUuid = subFrom3XUi.clientUuid?.takeIf { it.isNotBlank() }
                    ?: extractUuidFromVless(subFrom3XUi.vlessKey)
                    ?: user.id
                val fullSub = subFrom3XUi.copy(clientUuid = realClientUuid)
                _subscription.value = fullSub
                saveEmailCache(user.email, fullSub)
                prefs?.edit()
                    ?.putString(KEY_SUB_USER_ID, user.id)
                    ?.putString(KEY_SUB_EMAIL, user.email)
                    ?.putBoolean(KEY_SUB_IS_ACTIVE, true)
                    ?.putString(KEY_SUB_PLAN, "premium")
                    ?.putString(KEY_SUB_PAID_UNTIL, fullSub.paidUntil)
                    ?.putString(KEY_SUB_VLESS_KEY, fullSub.vlessKey)
                    ?.putString(KEY_SUB_CLIENT_UUID, realClientUuid)
                    ?.apply()
                markConfirmedAt()
                      AppLogger.info(TAG, "Subscription confirmed by server: until=${fullSub.paidUntil}")
                return@withContext
            }

            var supabaseQuerySucceeded = false
            try {
                val (statusCode, responseString) = fetchSubscriptionResponse(user.id)

                if (statusCode in 200..299 && responseString.isNotBlank()) {
                    val array = gson.fromJson(responseString, JsonArray::class.java)
                    supabaseQuerySucceeded = array != null
                    if (array != null && array.size() > 0) {
                        val sub = gson.fromJson(array.get(0), SubscriptionDto::class.java)
                        val hydratedSub = if (sub.flow == null) {
                            sub.copy(flow = fetchOptionalFlow(user.id))
                        } else {
                            sub
                        }
                        val clientUuid = hydratedSub.clientUuid ?: extractUuidFromVless(hydratedSub.vlessKey) ?: user.id
                        val fullSub = hydratedSub.copy(clientUuid = clientUuid)
                        if (fullSub.isActive && hasDateNotExpired(fullSub.paidUntil)) {

                            _subscription.value = fullSub
                            saveEmailCache(user.email, fullSub)
                            prefs?.edit()
                                ?.putString(KEY_SUB_USER_ID, user.id)
                                ?.putString(KEY_SUB_EMAIL, user.email)
                                ?.putBoolean(KEY_SUB_IS_ACTIVE, true)
                                ?.putString(KEY_SUB_PLAN, fullSub.plan)
                                ?.putString(KEY_SUB_PAID_UNTIL, fullSub.paidUntil)
                                ?.putString(KEY_SUB_VLESS_KEY, fullSub.vlessKey)
                                ?.putString(KEY_SUB_CLIENT_UUID, clientUuid)
                                ?.apply()
                            markConfirmedAt()
                              AppLogger.info(TAG, "Subscription loaded from Supabase: until=${fullSub.paidUntil}")
                            return@withContext
                        }
                    }
                } else {
                    AppLogger.w(TAG, "Supabase query returned HTTP $statusCode; preserving cached subscription")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Supabase subscription query failed: ${e.message}")
            }

            if (SubscriptionPolicy.shouldClearCache(
                    hasActiveCache = _subscription.value?.isActive == true,
                    supabaseAuthoritative = supabaseQuerySucceeded,
                    threeXUiAuthoritative = serverReached
                )
            ) {

                AppLogger.w(
                      TAG,
                      "Подписка отозвана сервером, кэш очищен для ${user.email}" +
                              (if (cacheAgeMinutes() >= 0) ", кэш был протух ${cacheAgeMinutes()} мин" else "")
                  )
                  AppLogger.info(TAG, "Supabase confirmed no active subscription for ${user.email}")
                clearEmailCache(user.email)
                clearSubscriptionPrefs()
                _subscription.value = SubscriptionDto(
                    userId = user.id,
                    email = user.email,
                    isActive = false,
                    plan = "free"
                )
                return@withContext
            }

            if (_subscription.value == null || _subscription.value?.isActive != true) {
                val cached = loadEmailCache(user.email)
                if (cached != null) {
                    _subscription.value = cached
                    AppLogger.info(TAG, "Kept email cache for ${user.email} (all online checks failed)")
                } else {
                    _subscription.value = SubscriptionDto(
                        userId = user.id,
                        email = user.email,
                        isActive = false,
                        plan = "free"
                    )
                    AppLogger.info(TAG, "No subscription found anywhere for ${user.email} — set to free")
                }
            } else {
                AppLogger.info(TAG, "Keeping existing cached subscription for ${user.email} (online checks failed)")
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Unexpected error in refreshSubscription: ${e.message}")

        }
    }

    private fun hasDateNotExpired(dateStr: String?): Boolean {
        return SubscriptionPolicy.isActive(true, dateStr)
    }

    suspend fun ensureFranceVlessKey(): String? = withContext(Dispatchers.IO) {
        if (!hasActiveSubscription()) return@withContext null
        val user = _currentUser.value ?: return@withContext null
        val sub = _subscription.value
        val knownUuid = sub?.clientUuid?.takeIf { it.isNotBlank() }
            ?: extractUuidFromVless(sub?.vlessKey)

        if (isCacheFresh()) {
            val cached = getActiveVlessKey()
            if (!cached.isNullOrBlank()) {
                AppLogger.i(
                    TAG,
                    "Ключ платного узла из кэша, возраст ${cacheAgeMinutes()} мин, панель не опрашивается"
                )
                return@withContext cached
            }
        } else {
            AppLogger.w(
                TAG,
                "Кэш подписки протух (${cacheAgeMinutes()} мин), ключ обновляется у панели"
            )
        }

        fun persistVerifiedKey(key: String, source: SubscriptionDto? = null): SubscriptionDto {
            val clientUuid = source?.clientUuid?.takeIf { it.isNotBlank() }
                ?: extractUuidFromVless(key)
                ?: knownUuid
                ?: user.id
            val updated = (sub ?: SubscriptionDto(
                userId = user.id,
                email = user.email,
                isActive = true,
                plan = "premium"
            )).copy(
                vlessKey = key,
                isActive = true,
                plan = "premium",
                paidUntil = source?.paidUntil ?: sub?.paidUntil,
                clientUuid = clientUuid,
                flow = source?.flow ?: sub?.flow
            )
            _subscription.value = updated
            saveEmailCache(user.email, updated)
            prefs?.edit()
                ?.putString(KEY_SUB_USER_ID, user.id)
                ?.putString(KEY_SUB_EMAIL, user.email)
                ?.putBoolean(KEY_SUB_IS_ACTIVE, true)
                ?.putString(KEY_SUB_PLAN, "premium")
                ?.putString(KEY_SUB_PAID_UNTIL, updated.paidUntil)
                ?.putString(KEY_SUB_VLESS_KEY, key)
                ?.putString(KEY_SUB_CLIENT_UUID, clientUuid)
                ?.putString(KEY_SUB_FLOW, updated.flow)
                ?.apply()
            return updated
        }

        val serverKey = fetchFranceConfigFromServer()
        if (!serverKey.isNullOrBlank()) {
            persistVerifiedKey(serverKey)
            markConfirmedAt()
                      AppLogger.i(TAG, "France key issued by france-config")
            return@withContext serverKey
        }

        AppLogger.w(TAG, "france-config недоступен, платный узел не будет подключён")

        val cachedKey = sub?.vlessKey ?: prefs?.getString(KEY_SUB_VLESS_KEY, null)
        if (!cachedKey.isNullOrBlank()) {
            AppLogger.w(TAG, "Используется кэшированный France-ключ: сервер выдал его при прошлом успешном входе")
            return@withContext cachedKey
        }

        AppLogger.w(TAG, "Нет верифицированного France-ключа ни с сервера, ни из кэша")
        null
    }

    fun extractUuidFromVless(vlessUrl: String?): String? {
        if (vlessUrl.isNullOrBlank()) return null
        val regex = Regex("""vless://([a-fA-F0-9\-]{36})@""")
        return regex.find(vlessUrl)?.groupValues?.get(1)
    }

    fun getActiveVlessKey(): String? {
        val sub = _subscription.value
        val vlessUrl = sub?.vlessKey ?: prefs?.getString(KEY_SUB_VLESS_KEY, null)
        if (!vlessUrl.isNullOrBlank() && vlessUrl.startsWith("vless://")) {
            return vlessUrl
        }
        return null
    }

    fun getActiveClientUuid(): String {
        val sub = _subscription.value
        val vlessUrl = sub?.vlessKey ?: prefs?.getString(KEY_SUB_VLESS_KEY, null)
        val extracted = extractUuidFromVless(vlessUrl)
        if (!extracted.isNullOrBlank()) return extracted

        val clientUuid = sub?.clientUuid ?: prefs?.getString(KEY_SUB_CLIENT_UUID, null)
        if (!clientUuid.isNullOrBlank() && clientUuid.length == 36) return clientUuid

        return ""
    }

    fun getActiveClientFlow(clientUuid: String? = null): String {
        val sub = _subscription.value
        if (!sub?.flow.isNullOrBlank()) return sub!!.flow!!

        val vlessUrl = sub?.vlessKey ?: prefs?.getString(KEY_SUB_VLESS_KEY, null)
        if (!vlessUrl.isNullOrBlank()) {
            val regex = Regex("""[?&]flow=([^&#]+)""")
            val match = regex.find(vlessUrl)
            if (match != null) return match.groupValues[1]
        }

        val targetUuid = clientUuid ?: getActiveClientUuid()
        if (targetUuid.isBlank()) {

            return ""
        }

        return ""
    }

    fun hasActiveSubscription(): Boolean {
        val user = _currentUser.value ?: return false
        val sub = _subscription.value ?: return false
        if (!sub.isActive) return false
        if (!sub.email.trim().equals(user.email.trim(), ignoreCase = true) && sub.userId != user.id) {
            return false
        }
        return SubscriptionPolicy.isActive(sub.isActive, sub.paidUntil)
    }

    fun confirmedAt(): Long = prefs?.getLong(KEY_SUB_CONFIRMED_AT, 0L) ?: 0L

    fun isCacheFresh(now: Long = System.currentTimeMillis()): Boolean {
        val at = confirmedAt()
        return at > 0L && now - at < CACHE_FRESH_MS
    }

    fun cacheAgeMinutes(now: Long = System.currentTimeMillis()): Long {
        val at = confirmedAt()
        if (at <= 0L) return -1L
        return (now - at) / 60_000L
    }

    private fun markConfirmedAt(now: Long = System.currentTimeMillis()) {
        prefs?.edit()?.putLong(KEY_SUB_CONFIRMED_AT, now)?.apply()
    }
}
