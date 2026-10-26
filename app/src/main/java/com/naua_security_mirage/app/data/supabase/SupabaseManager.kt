package com.naua_security_mirage.app.data.supabase

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.naua_security_mirage.app.R
import com.naua_security_mirage.app.data.config.PaidNode
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.security.MessageDigest
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
    @SerializedName("flow") val flow: String? = null,
      @SerializedName("belarus_vless_key") val belarusVlessKey: String? = null,
      @SerializedName("belarus_client_uuid") val belarusClientUuid: String? = null
)

class SupabaseManager private constructor() {

    companion object {
        private const val TAG = "SupabaseManager"

        // Публичный web client ID Google-провайдера Supabase. Нужен, чтобы
        // получить ID token через Credential Manager и не гонять браузер.
        private const val GOOGLE_WEB_CLIENT_ID =
            "13502295152-0ft939doqn0v4o39im3ocae2s0qae6m9.apps.googleusercontent.com"

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
        private const val KEY_SUB_BELARUS_VLESS_KEY = "sub_belarus_vless_key"
        private const val KEY_SUB_BELARUS_CLIENT_UUID = "sub_belarus_client_uuid"
        private const val KEY_SUB_NODE_PREFIX = "sub_node_vless_"

        private const val KEY_OAUTH_STATE = "oauth_state_pending"

        private const val KEY_OAUTH_STATE_AT = "oauth_state_pending_at"

        private const val KEY_OAUTH_VERIFIER = "oauth_code_verifier"

        private const val KEY_OAUTH_ATTEMPTS = "oauth_attempts_pending"

        private const val KEY_LAST_EXCHANGED_CODE = "oauth_last_exchanged_code"

        private const val KEY_SUB_CONFIRMED_AT = "sub_confirmed_at"

        private const val CACHE_FRESH_MS = 10 * 60 * 1000L

        // Час, а не десять минут: вход через Google в медленной сети (выбор
        // аккаунта, двухфакторка, переключение сети) легко занимает больше
        // десяти минут, и попытка считалась потерянной ещё до ответа.
        private const val OAUTH_STATE_TTL_MS = 60 * 60 * 1000L

        private const val MAX_OAUTH_ATTEMPTS = 5

        private const val MAX_CODE_EXCHANGE_ATTEMPTS = 3

        private const val PKCE_VERIFIER_BYTES = 64

        val instance by lazy { SupabaseManager() }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val httpClient = OkHttpClient.Builder()
        .forSupabase()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var prefsEncrypted = false
    // Если Keystore недоступен, попытки OAuth храним только в памяти:
    // иначе verifier попал бы в открытый файл. После перезапуска процесса
    // вход нужно начать заново.
    private val oauthAttemptsFallback =
        java.util.Collections.synchronizedList(mutableListOf<OAuthAttempt>())

    private fun httpError(code: Int): String =
        appContext?.getString(R.string.response_error_fmt, code) ?: "Error $code"
    private var accessToken: String? = null
        set(value) {
            field = value
            AppLogger.registerSecrets(value)
        }

    private var refreshToken: String? = null
        set(value) {
            field = value
            AppLogger.registerSecrets(value)
        }
    private var lastExchangedCode: String? = null
    @Volatile private var belarusPrefetchRunning = false

    private val _currentUser = MutableStateFlow<SupabaseUser?>(null)
    val currentUser: StateFlow<SupabaseUser?> = _currentUser.asStateFlow()

    private val _subscription = MutableStateFlow<SubscriptionDto?>(null)
    val subscription: StateFlow<SubscriptionDto?> = _subscription.asStateFlow()

    private fun clearSubscriptionPrefs() {
        val editor = prefs?.edit()
            ?.remove(KEY_SUB_USER_ID)
            ?.remove(KEY_SUB_EMAIL)
            ?.remove(KEY_SUB_IS_ACTIVE)
            ?.remove(KEY_SUB_PLAN)
            ?.remove(KEY_SUB_PAID_UNTIL)
            ?.remove(KEY_SUB_VLESS_KEY)
            ?.remove(KEY_SUB_CLIENT_UUID)
            ?.remove(KEY_SUB_FLOW)
            ?.remove(KEY_SUB_BELARUS_VLESS_KEY)
            ?.remove(KEY_SUB_BELARUS_CLIENT_UUID)
            ?.remove(KEY_SUB_CONFIRMED_AT)
        // Server-driven nodes cache their key per node id. A signed-out device
        // must not be able to open a paid node with a leftover key.
        prefs?.all?.keys
            ?.filter { it.startsWith(KEY_SUB_NODE_PREFIX) }
            ?.forEach { editor?.remove(it) }
        editor?.apply()
    }

    private fun emailCacheKey(email: String): String {
        return "email_sub_${email.trim().lowercase()}"
    }

    private fun saveEmailCache(email: String, sub: SubscriptionDto) {
        try {
            prefs?.edit()?.putString(emailCacheKey(email), gson.toJson(sub))?.apply()
            AppLogger.info(TAG, "Email cache saved for account: plan=${sub.plan}, uuid=${sub.clientUuid}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to save email cache: ${e.message}")
        }
    }

    private fun loadEmailCache(email: String): SubscriptionDto? {
        return try {
            val json = prefs?.getString(emailCacheKey(email), null) ?: return null
            val dto = gson.fromJson(json, SubscriptionDto::class.java)
            if (dto != null && SubscriptionPolicy.isActive(dto.isActive, dto.paidUntil)) {
                AppLogger.info(TAG, "Email cache loaded for account: plan=${dto.plan}, uuid=${dto.clientUuid}")
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
        AppLogger.info(TAG, "Email cache cleared for account")
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

            AppLogger.w(TAG, "Зашифрованное хранилище недоступно, сессия не будет сохранена: ${e.message}")
            prefsEncrypted = false
            return plain
        }

        // Переносим значения с сохранением типа: раньше здесь был getString,
        // а в старом файле лежат и Long (время попытки входа), и Boolean
        // (признак подписки) — на них миграция падала с ClassCastException
        // и роняла запуск приложения.
        var migrated = 0
        for ((key, value) in plain.all) {
            if (encrypted.contains(key)) {
                plain.edit().remove(key).apply()
                continue
            }
            val editor = encrypted.edit()
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> continue
            }
            editor.commit()
            plain.edit().remove(key).apply()
            migrated++
        }
        if (migrated > 0) {
            plain.edit().clear().commit()
            AppLogger.i(TAG, "Сессия перенесена в зашифрованное хранилище")
        }
        prefsEncrypted = true
        return encrypted
    }

    // Токен текущей сессии. Нужен, чтобы привязать устройство к аккаунту
    // при регистрации FCM-токена: без него устройство остаётся «ничьим».
    fun accessTokenOrNull(): String? = accessToken

    fun init(context: Context) {
        appContext = context.applicationContext
        if (prefs == null || !prefsEncrypted) {
            val opened = openAuthPrefs(context)
            if (!prefsEncrypted) {
                // Keystore недоступен: не держим refresh-токены и VLESS-ключи
                // в открытом SharedPreferences. Сессия живёт только в памяти,
                // после перезапуска пользователь войдёт заново.
                runCatching { opened.edit().clear().apply() }
                prefs = null
                accessToken = null
                refreshToken = null
                _currentUser.value = null
                _subscription.value = null
                AppLogger.w(TAG, "Шифрованное хранилище недоступно: токены и ключи не сохраняются")
                return
            }
            prefs = opened
            val savedToken = prefs?.getString(KEY_ACCESS_TOKEN, null)
            val savedRefreshToken = prefs?.getString(KEY_REFRESH_TOKEN, null)
            val savedId = prefs?.getString(KEY_USER_ID, null)
            val savedEmail = prefs?.getString(KEY_USER_EMAIL, null)
            lastExchangedCode = prefs?.getString(KEY_LAST_EXCHANGED_CODE, null)

            if (!savedToken.isNullOrEmpty() && !savedId.isNullOrEmpty() && !savedEmail.isNullOrEmpty()) {
                accessToken = savedToken
                refreshToken = savedRefreshToken
                _currentUser.value = SupabaseUser(savedId, savedEmail)

                val cached = loadEmailCache(savedEmail)
                if (cached != null) {
                    _subscription.value = cached
                    AppLogger.info(TAG, "Init: subscription loaded from email cache")
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
                            flow = prefs?.getString(KEY_SUB_FLOW, null),
                            belarusVlessKey = prefs?.getString(KEY_SUB_BELARUS_VLESS_KEY, null),
                            belarusClientUuid = prefs?.getString(KEY_SUB_BELARUS_CLIENT_UUID, null)
                        )
                        _subscription.value = sub

                        saveEmailCache(savedEmail, sub)
                        AppLogger.info(TAG, "Init: migrated old-style prefs to email cache")
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
                        ?: httpError(response.code)
                } catch (_: Exception) {
                    httpError(response.code)
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
                        ?: httpError(response.code)
                } catch (_: Exception) {
                    httpError(response.code)
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

    /**
     * Сначала пробуем нативный ID token через Credential Manager: он не
     * зависит от браузера и медленного редиректа Supabase. Если Google
     * Play Services или OAuth-клиент не настроены, откатываемся на браузер.
     *
     * @return пользователь, если вход уже завершён нативно; null — открыт браузер.
     */
    suspend fun signInWithGoogle(activity: Activity): SupabaseUser? {
        // Первый вход иногда не срабатывает (кеш Credential Manager/сеть),
        // а повторный сразу проходит. Даём вторую попытку до браузера.
        repeat(2) {
            when (val native = requestGoogleIdToken(activity)) {
                is NativeGoogleResult.Token -> {
                    exchangeGoogleIdToken(native.idToken, native.rawNonce).getOrNull()?.let { return it }
                    AppLogger.w(TAG, "Нативный вход не удался, повторяю")
                }
                NativeGoogleResult.Cancelled -> return null
                NativeGoogleResult.Unavailable -> Unit
            }
        }
        AppLogger.w(TAG, "Нативный вход не удался, открываю браузер")
        openGoogleOAuth(activity)
        return null
    }

    private sealed interface NativeGoogleResult {
        data class Token(val idToken: String, val rawNonce: String) : NativeGoogleResult
        data object Cancelled : NativeGoogleResult
        data object Unavailable : NativeGoogleResult
    }

    private suspend fun requestGoogleIdToken(activity: Activity): NativeGoogleResult {
        return try {
            val rawNonce = UUID.randomUUID().toString()
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(GOOGLE_WEB_CLIENT_ID)
                .setNonce(sha256Hex(rawNonce))
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val credential = CredentialManager.create(activity)
                .getCredential(activity, request)
                .credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                NativeGoogleResult.Token(
                    GoogleIdTokenCredential.createFrom(credential.data).idToken,
                    rawNonce
                )
            } else {
                AppLogger.w(TAG, "Google native sign-in: неожиданный тип credential")
                NativeGoogleResult.Unavailable
            }
        } catch (e: GetCredentialCancellationException) {
            AppLogger.info(TAG, "Google native sign-in отменён")
            NativeGoogleResult.Cancelled
        } catch (e: Exception) {
            AppLogger.w(
                TAG,
                "Google native sign-in недоступен: ${e.javaClass.simpleName}: ${e.message}"
            )
            NativeGoogleResult.Unavailable
        }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private suspend fun exchangeGoogleIdToken(
        idToken: String,
        nonce: String
    ): Result<SupabaseUser> =
        withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("id_token", idToken)
                    addProperty("provider", "google")
                    addProperty("nonce", nonce)
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val anon = SupabaseConfig.getAnonKey()
                val request = Request.Builder()
                    .url("${SupabaseConfig.SUPABASE_URL}/auth/v1/token?grant_type=id_token")
                    .header("apikey", anon)
                    .header("Authorization", "Bearer $anon")
                    .post(body)
                    .build()

                // Обмен отвечает меньше секунды; 20 с хватает даже на слабой
                // сети и не растягивает вторую попытку входа.
                httpClient.newBuilder()
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val reason = reasonFrom(text).ifBlank { "HTTP ${response.code}" }
                        AppLogger.w(TAG, "Google ID token отклонён: HTTP ${response.code} $reason")
                        return@withContext Result.failure(Exception(reason))
                    }
                    val json = gson.fromJson(text, JsonObject::class.java)
                        ?: return@withContext Result.failure(Exception("пустой ответ"))
                    val token = json.get("access_token")?.asString
                    val userObj = json.getAsJsonObject("user")
                    val uid = userObj?.get("id")?.asString.orEmpty()
                    val email = userObj?.get("email")?.asString.orEmpty()
                    if (token.isNullOrEmpty() || uid.isEmpty()) {
                        AppLogger.w(TAG, "Google ID token: в ответе нет токена или пользователя")
                        return@withContext Result.failure(Exception("в ответе нет токена или пользователя"))
                    }
                    json.get("refresh_token")?.asString?.let { refreshToken = it }
                    val user = SupabaseUser(uid, email)
                    saveSession(token, user)
                    Result.success(user)
                }
            } catch (e: Exception) {
                AppLogger.w(
                    TAG,
                    "Google ID token exchange failed: ${e.javaClass.simpleName}: ${e.message}"
                )
                Result.failure(e)
            }
        }

    private fun openGoogleOAuth(context: Context) {
        try {
            val redirectUri = Uri.encode("mirage://auth/callback")
            val state = randomUrlSafeToken(24)
            val verifier = randomPkceVerifier()
            val challenge = pkceChallengeS256(verifier)
            AppLogger.registerSecrets(verifier, challenge, state)

            saveOAuthAttempt(state, verifier)

            val oauthUrl = "${SupabaseConfig.SUPABASE_URL}/auth/v1/authorize?provider=google" +
                "&redirect_to=$redirectUri&state=$state" +
                "&flow_type=pkce&code_challenge=$challenge&code_challenge_method=S256"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(oauthUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            AppLogger.error(TAG, "Google sign in error: ${e.javaClass.simpleName}: ${e.message}")
            AppLogger.onUserMessage(context.getString(R.string.auth_google_open_fail))
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

    /**
     * Попытки входа, которые ещё не обменяли на сессию.
     *
     * Их несколько, потому что вход можно начать повторно, пока старый ещё
     * открыт в браузере: тогда вернётся код от первой попытки, а верификатор
     * уже перезаписан. Сохраняем все, чтобы такой код всё равно обменялся.
     */
    private data class OAuthAttempt(
        val state: String,
        val issuedAt: Long,
        val verifier: String
    )

    private fun saveOAuthAttempt(state: String, verifier: String) {
        val now = System.currentTimeMillis()
        val attempts = loadOAuthAttempts()
            .filter { now - it.issuedAt <= OAUTH_STATE_TTL_MS }
            .toMutableList()
        attempts.add(OAuthAttempt(state, now, verifier))
        while (attempts.size > MAX_OAUTH_ATTEMPTS) {
            attempts.removeAt(0)
        }
        synchronized(oauthAttemptsFallback) {
            oauthAttemptsFallback.clear()
            oauthAttemptsFallback.addAll(attempts)
        }
        if (prefsEncrypted) {
            prefs?.edit()
                ?.putString(KEY_OAUTH_ATTEMPTS, gson.toJson(attempts))
                ?.putString(KEY_OAUTH_STATE, state)
                ?.putLong(KEY_OAUTH_STATE_AT, now)
                ?.putString(KEY_OAUTH_VERIFIER, verifier)
                ?.commit()
        }
        AppLogger.info(TAG, "OAuth attempt saved: total=${attempts.size}, ttlMs=$OAUTH_STATE_TTL_MS")
    }

    private fun loadOAuthAttempts(): List<OAuthAttempt> {
        val stored = loadOAuthAttemptsList()
        if (stored.isNotEmpty()) return stored

        // Резерв: одиночные ключи пишутся вместе со списком. Если список не
        // прочитался (старая версия, сбой сериализации), попытка не теряется.
        val state = runCatching { prefs?.getString(KEY_OAUTH_STATE, null) }.getOrNull()
        val verifier = runCatching { prefs?.getString(KEY_OAUTH_VERIFIER, null) }.getOrNull()
        val issuedAt = runCatching { prefs?.getLong(KEY_OAUTH_STATE_AT, 0L) }.getOrNull() ?: 0L
        if (state.isNullOrBlank() || verifier.isNullOrBlank()) return emptyList()
        return listOf(OAuthAttempt(state, if (issuedAt > 0) issuedAt else System.currentTimeMillis(), verifier))
    }

    private fun loadOAuthAttemptsList(): List<OAuthAttempt> {
        if (!prefsEncrypted || prefs == null) {
            return synchronized(oauthAttemptsFallback) { oauthAttemptsFallback.toList() }
        }
        val raw = prefs?.getString(KEY_OAUTH_ATTEMPTS, null) ?: return emptyList()
        return try {
            val array = gson.fromJson(raw, JsonArray::class.java) ?: return emptyList()
            array.mapNotNull { element ->
                val obj = element.asJsonObject
                val state = obj.get("state")?.asString
                val verifier = obj.get("verifier")?.asString
                val issuedAt = obj.get("issuedAt")?.asLong ?: 0L
                if (state.isNullOrBlank() || verifier.isNullOrBlank()) null
                else OAuthAttempt(state, issuedAt, verifier)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun pickOAuthAttempt(returnedState: String?): OAuthAttempt? {
        val picked = OAuthCallbackPolicy.pickAttempt(
            attempts = loadOAuthAttempts().map {
                OAuthCallbackPolicy.Attempt(it.state, it.issuedAt, it.verifier)
            },
            returnedState = returnedState,
            now = System.currentTimeMillis(),
            stateTtlMs = OAUTH_STATE_TTL_MS
        )
        if (picked == null) return null
        return OAuthAttempt(picked.state, picked.issuedAt, picked.verifier)
    }

    private fun clearOAuthAttempt(attempt: OAuthAttempt) {
        val rest = loadOAuthAttemptsList().filter { it.state != attempt.state }
        synchronized(oauthAttemptsFallback) {
            oauthAttemptsFallback.clear()
            oauthAttemptsFallback.addAll(rest)
        }
        if (!prefsEncrypted || prefs == null) return
        val editor = prefs?.edit()?.putString(KEY_OAUTH_ATTEMPTS, gson.toJson(rest))
        if (prefs?.getString(KEY_OAUTH_STATE, null) == attempt.state) {
            editor?.remove(KEY_OAUTH_STATE)?.remove(KEY_OAUTH_STATE_AT)?.remove(KEY_OAUTH_VERIFIER)
        }
        editor?.commit()
    }

    private sealed interface CodeExchangeResult {
        data class Success(val token: String, val user: SupabaseUser) : CodeExchangeResult
        data class Failure(val reason: String, val retryable: Boolean) : CodeExchangeResult
    }

    private suspend fun exchangePkceCode(code: String, verifier: String): CodeExchangeResult {
        var attempt = 0
        var lastReason = "неизвестная ошибка"
        while (attempt < MAX_CODE_EXCHANGE_ATTEMPTS) {
            attempt++
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

            val outcome: CodeExchangeResult = try {
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val reason = reasonFrom(text).ifBlank { "HTTP ${response.code}" }
                        val retryable = response.code == 408 || response.code == 429 || response.code >= 500
                        AppLogger.w(TAG, "Обмен кода отклонён: HTTP ${response.code} $reason")
                        CodeExchangeResult.Failure(reason, retryable)
                    } else {
                        val json = gson.fromJson(text, JsonObject::class.java)
                        val token = json?.get("access_token")?.asString
                        if (token.isNullOrEmpty()) {
                            val reason = reasonFrom(text).ifBlank { "в ответе нет токена" }
                            AppLogger.w(TAG, "Обмен кода: $reason")
                            CodeExchangeResult.Failure(reason, false)
                        } else {
                            val userObj = json.getAsJsonObject("user")
                            val uid = userObj?.get("id")?.asString.orEmpty()
                            val email = userObj?.get("email")?.asString.orEmpty()
                            if (uid.isEmpty()) {
                                AppLogger.w(TAG, "Обмен кода: в ответе нет пользователя")
                                CodeExchangeResult.Failure("в ответе нет пользователя", false)
                            } else {
                                json.get("refresh_token")?.asString?.let { refreshToken = it }
                                CodeExchangeResult.Success(token, SupabaseUser(uid, email))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                lastReason = "${e.javaClass.simpleName}: ${e.message}"
                AppLogger.w(TAG, "Обмен кода не выполнен: $lastReason")
                CodeExchangeResult.Failure(lastReason, true)
            }

            when (outcome) {
                is CodeExchangeResult.Success -> return outcome
                is CodeExchangeResult.Failure -> {
                    if (outcome.retryable && attempt < MAX_CODE_EXCHANGE_ATTEMPTS) {
                        delay(1200)
                        continue
                    }
                    return outcome
                }
            }
        }
        return CodeExchangeResult.Failure(lastReason, true)
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

    private suspend fun saveSession(
        token: String,
        user: SupabaseUser,
        exchangedCode: String? = null
    ) {
        if (exchangedCode != null) lastExchangedCode = exchangedCode
        accessToken = token
        _currentUser.value = user
        _subscription.value = loadEmailCache(user.email)

        val editor = prefs?.edit()
            ?.putString(KEY_ACCESS_TOKEN, token)
            ?.putString(KEY_REFRESH_TOKEN, refreshToken)
            ?.putString(KEY_USER_ID, user.id)
            ?.putString(KEY_USER_EMAIL, user.email)
        if (exchangedCode != null) {
            editor?.putString(KEY_LAST_EXCHANGED_CODE, exchangedCode)
        }
        editor?.apply()

        refreshSubscription()
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

            // Ошибку провайдера показываем как есть: она информативнее, чем
            // «ответ не соответствует начатому входу».
            val error = params["error_description"] ?: params["error"]
            if (!error.isNullOrBlank()) return@withContext Result.failure(Exception(error))

            val code = params["code"]
            val returnedState = params["state"]
            val attemptsNow = loadOAuthAttempts()
            val newestAge = attemptsNow.maxOfOrNull { System.currentTimeMillis() - it.issuedAt } ?: -1L
            AppLogger.info(
                TAG,
                "OAuth callback: code=${!code.isNullOrBlank()}, state=${!returnedState.isNullOrBlank()}, " +
                    "attempts=${attemptsNow.size}, newestAgeMs=$newestAge, ttlMs=$OAUTH_STATE_TTL_MS"
            )

            fun reject(reason: String, messageRes: Int = R.string.auth_response_mismatch): Result<SupabaseUser> {
                AppLogger.w(TAG, "OAuth отклонён: $reason")
                return Result.failure(
                    Exception(appContext?.getString(messageRes) ?: "Auth response mismatch")
                )
            }

            // Тот же код может прийти второй раз: пересоздание экрана или
            // повторная доставка интента. Это не ошибка — сессия уже есть.
            if (!code.isNullOrBlank() && lastExchangedCode == code) {
                _currentUser.value?.let { existing ->
                    AppLogger.i(TAG, "OAuth callback повторный, сессия уже создана")
                    return@withContext Result.success(existing)
                }
            }

            val attempt = pickOAuthAttempt(returnedState)
                ?: return@withContext reject("нет начатого входа", R.string.auth_start_over)

            val verdict = OAuthCallbackPolicy.evaluate(
                expectedState = attempt.state,
                issuedAt = attempt.issuedAt,
                now = System.currentTimeMillis(),
                returnedState = returnedState,
                code = code,
                verifier = attempt.verifier,
                stateTtlMs = OAUTH_STATE_TTL_MS
            )
            if (verdict is OAuthCallbackPolicy.Verdict.Reject) return@withContext reject(verdict.reason)

            val accepted = verdict as OAuthCallbackPolicy.Verdict.Accept
            val acceptedCode = code!!

            when (val exchange = exchangePkceCode(acceptedCode, accepted.codeVerifier)) {
                is CodeExchangeResult.Success -> {
                    val token = exchange.token
                    val user = exchange.user
                    clearOAuthAttempt(attempt)

                    saveSession(token, user, acceptedCode)
                    AppLogger.info(TAG, "OAuth user logged in (cached sub: ${_subscription.value?.plan ?: "none"})")
                    Result.success(user)
                }
                is CodeExchangeResult.Failure -> {
                    if (!exchange.retryable) {
                        clearOAuthAttempt(attempt)
                    }
                    AppLogger.w(TAG, "Обмен кода не удался: ${exchange.reason}")
                    val message = appContext?.getString(
                        R.string.auth_code_exchange_fail_reason,
                        exchange.reason
                    ) ?: "Code exchange failed: ${exchange.reason}"
                    Result.failure(Exception(message))
                }
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "OAuth callback error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Обновляет access token ровно один раз, даже если 401 одновременно
     * получили несколько запросов. Supabase выдаёт новый refresh token при
     * каждом обновлении, поэтому параллельные обновления одним и тем же
     * токеном приводили к `refresh_token_already_used` и молчаливому
     * разлогину.
     *
     * Если за время ожидания другой поток уже обновил токен, повторный
     * запрос не нужен: достаточно сообщить вызывающему, что можно повторить
     * запрос с новым access token.
     */
    @Synchronized
    private fun refreshAccessToken(staleAccessToken: String?): Boolean {
        val currentAccess = accessToken
        if (!staleAccessToken.isNullOrEmpty() &&
            !currentAccess.isNullOrEmpty() &&
            currentAccess != staleAccessToken
        ) {
            return true
        }

        val token = refreshToken ?: run {
            onSessionExpired("refresh token отсутствует")
            return false
        }
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
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorJson = try {
                    gson.fromJson(responseBody, JsonObject::class.java)
                } catch (_: Exception) {
                    null
                }
                val errorCode = errorJson?.get("error_code")?.asString
                    ?: errorJson?.get("error")?.asString
                AppLogger.w(
                    TAG,
                    "Access token refresh failed: HTTP ${response.code}" +
                            (errorCode?.let { " ($it)" } ?: "")
                )
                if (response.code == 400 && (
                            errorCode == "refresh_token_not_found" ||
                                    errorCode == "invalid_grant" ||
                                    errorCode == "refresh_token_revoked" ||
                                    errorCode == "refresh_token_already_used"
                            )
                ) {
                    // `already_used` может быть гонкой двух обновлений. Если
                    // access token уже сменился, сессия жива, и выходить не
                    // нужно.
                    if (!accessToken.isNullOrEmpty() && accessToken != staleAccessToken) {
                        return true
                    }
                    onSessionExpired(errorCode)
                }
                return false
            }
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val newAccess = json.get("access_token")?.asString ?: return false
            val newRefresh = json.get("refresh_token")?.asString
            accessToken = newAccess
            if (newRefresh != null) refreshToken = newRefresh
            val editor = prefs?.edit()?.putString(KEY_ACCESS_TOKEN, newAccess)
            if (newRefresh != null) editor?.putString(KEY_REFRESH_TOKEN, newRefresh)
            editor?.apply()
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Access token refresh failed: ${e.message}")
            false
        }
    }

    /**
     * Сессия больше не восстанавливается: refresh token отозван, истёк или
     * удалён на сервере. Держать пользователя в состоянии «подписка есть, но
     * ключ не выдаётся» нельзя — он должен увидеть вход и войти заново.
     */
    @Synchronized
    private fun onSessionExpired(reason: String) {
        if (_currentUser.value == null) return
        AppLogger.w(TAG, "Сессия истекла ($reason), требуется повторный вход")
        signOut()
        appContext?.let { ctx ->
            AppLogger.onUserMessage(ctx.getString(R.string.auth_session_expired))
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

            AppLogger.info(TAG, "Premium subscription activated locally, clientUuid=$clientUuid")
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to activate premium subscription: ${e.message}")
            false
        }
    }

    private suspend fun fetchFranceConfigFromServer(): String? = withContext(Dispatchers.IO) {
        when (val result = fetchFranceConfig()) {
            is FranceConfigResult.Key -> {
                when (result.trafficSeen) {
                    true -> AppLogger.i(TAG, "Сервер видел трафик этого клиента")
                    false -> AppLogger.w(TAG, "Сервер не видел трафика от клиента: авторизация, вероятно, отклоняется")
                    null -> Unit
                }
                result.vlessKey
            }
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
        data class Key(val vlessKey: String, val clientUuid: String, val paidUntil: String?, val trafficSeen: Boolean? = null) : FranceConfigResult()
        object NoSubscription : FranceConfigResult()
        data class Unavailable(val reason: String) : FranceConfigResult()
    }

    /**
     * Просит у сервера пробный день: один бесплатный день платного узла.
     *
     * Решение принимает сервер (supabase/functions/free-trial): он же проверяет,
     * что пробник ещё не выдавался этому аккаунту и этому устройству, и сам
     * записывает пробник в подписку. Клиент ничего не решает и ничего не может
     * подделать — подписку пишет только сервер.
     */
    suspend fun requestFreeTrial(deviceKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = accessToken
            if (token.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("нет сессии"))
            }
            val body = JsonObject().apply {
                addProperty("device_key", deviceKey)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            fun call(bearer: String): Response {
                val request = Request.Builder()
                    .url("${SupabaseConfig.SUPABASE_URL}/functions/v1/free-trial")
                    .header("apikey", SupabaseConfig.getAnonKey())
                    .header("Authorization", "Bearer $bearer")
                    .post(body)
                    .build()
                return httpClient.newCall(request).execute()
            }

            var response = call(token)
            if (response.code == 401 && refreshAccessToken(token)) {
                val refreshed = accessToken
                if (refreshed.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("токен не обновился"))
                }
                response = call(refreshed)
            }

            val text = response.body?.string().orEmpty()
            if (response.code == 409) {
                return@withContext Result.failure(Exception("Пробный день уже использован"))
            }
            if (!response.isSuccessful) {
                val reason = try {
                    gson.fromJson(text, JsonObject::class.java)
                        ?.get("error")?.asString
                } catch (_: Exception) {
                    null
                } ?: "HTTP ${response.code}"
                return@withContext Result.failure(Exception(reason))
            }

            val json = gson.fromJson(text, JsonObject::class.java)
            val paidUntil = json?.get("paidUntil")?.asString.orEmpty()
            if (paidUntil.isBlank()) {
                return@withContext Result.failure(Exception("сервер не вернул срок пробника"))
            }
            AppLogger.i(TAG, "Пробный день выдан до $paidUntil")

            // Подписку подтягиваем с сервера, чтобы приложение увидело план
            // «trial» и активный срок.
            refreshSubscription()
            Result.success(paidUntil)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Пробный день не выдан: ${e.message}")
            Result.failure(e)
        }
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
                // Конфиг не критичен (есть кэш), а его таймаут задерживает
                // загрузку подписки, поэтому ждём не дольше 10 с.
                return httpClient.newBuilder()
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                    .newCall(request).execute()
            }

            var response = call(token)
            if (response.code == 401 && refreshAccessToken(token)) {
                val refreshed = accessToken
                if (refreshed.isNullOrEmpty()) {
                    return@withContext FranceConfigResult.Unavailable("токен не обновился")
                }
                response = call(refreshed)
            } else if (response.code == 401 && _currentUser.value == null) {
                return@withContext FranceConfigResult.Unavailable("сессия истекла")
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
                            paidUntil = json.get("paidUntil")?.takeIf { !it.isJsonNull }?.asString,
                            trafficSeen = json.get("trafficSeen")?.takeIf { !it.isJsonNull }?.asBoolean
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
                .url("${SupabaseConfig.SUPABASE_URL}/rest/v1/subscriptions?user_id=eq.${android.net.Uri.encode(userId)}&select=user_id,email,is_active,plan,paid_until,vless_key,client_uuid,updated_at")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", authHeader)
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            return response.code to response.body?.string().orEmpty()
        }

        val staleToken = accessToken
        val first = execute()
        return if (first.first == 401 && refreshAccessToken(staleToken)) execute() else first
    }

    private fun fetchOptionalFlow(userId: String): String? {
        return try {
            val authHeader = if (!accessToken.isNullOrEmpty()) "Bearer $accessToken" else "Bearer ${SupabaseConfig.getAnonKey()}"
            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL}/rest/v1/subscriptions?user_id=eq.${android.net.Uri.encode(userId)}&select=flow&limit=1")
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

    suspend fun refreshSubscription() {
        refreshMutex.withLock { refreshSubscriptionUnsafe() }
        prefetchBelarusKeyIfMissing()
    }

    /**
     * Заранее забирает ключ Беларуси, чтобы к моменту нажатия «Подключить»
     * он уже лежал в кэше. Иначе первый запрос к belarus-config приходится
     * ровно на старт VPN и упирается в таймаут.
     */
    private fun prefetchBelarusKeyIfMissing() {
        if (!hasActiveSubscription()) return
        if (!getActiveBelarusVlessKey().isNullOrBlank()) return
        if (belarusPrefetchRunning) return
        belarusPrefetchRunning = true
        scope.launch {
            try {
                ensureBelarusVlessKey()
            } catch (_: Throwable) {
            } finally {
                belarusPrefetchRunning = false
            }
        }
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

            // Обновление токена могло выяснить, что сессия отозвана.
            // После signOut нельзя записывать подписку от имени старого
            // пользователя — иначе UI снова покажет оплаченный план.
            if (_currentUser.value == null) return@withContext

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
            var expiredPaidUntil: String? = null
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
                        expiredPaidUntil = fullSub.paidUntil
                        AppLogger.w(
                            TAG,
                            "Supabase subscription неактивна: active=${fullSub.isActive}, until=${fullSub.paidUntil}"
                        )
                    }
                    if (array != null && array.size() == 0) {
                        AppLogger.w(TAG, "Supabase subscription: строк нет")
                    }
                } else {
                    AppLogger.w(TAG, "Supabase query returned HTTP $statusCode; preserving cached subscription")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Supabase subscription query failed: ${e.message}")
            }

            if (_currentUser.value == null) return@withContext

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
                  AppLogger.info(TAG, "Supabase confirmed no active subscription for account")
                clearEmailCache(user.email)
                clearSubscriptionPrefs()
                _subscription.value = SubscriptionDto(
                    userId = user.id,
                    email = user.email,
                    isActive = false,
                    plan = "free",
                    paidUntil = expiredPaidUntil
                )
                return@withContext
            }

            if (_subscription.value == null || _subscription.value?.isActive != true) {
                val cached = loadEmailCache(user.email)
                if (cached != null) {
                    _subscription.value = cached
                    AppLogger.info(TAG, "Kept email cache (all online checks failed)")
                } else {
                    _subscription.value = SubscriptionDto(
                        userId = user.id,
                        email = user.email,
                        isActive = false,
                        plan = "free"
                    )
                    AppLogger.info(TAG, "No subscription found anywhere for account — set to free")
                }
            } else {
                AppLogger.info(TAG, "Keeping existing cached subscription (online checks failed)")
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

    fun getActiveBelarusVlessKey(): String? {
        val sub = _subscription.value
        return sub?.belarusVlessKey ?: prefs?.getString(KEY_SUB_BELARUS_VLESS_KEY, null)
    }

    /**
     * Сбрасывает пул HTTP-соединений.
     *
     * После перезапуска VPN в пуле остаются сокеты, которые шли через старый
     * туннель: они уже мертвы, но выглядят живыми, и запрос висит до
     * read-timeout. Сброс заставляет открыть новое соединение.
     */
    fun resetHttpConnections() {
        try {
            httpClient.connectionPool.evictAll()
        } catch (_: Throwable) {
        }
    }

    suspend fun ensureBelarusVlessKey(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!hasActiveSubscription()) return@withContext null
        _currentUser.value ?: return@withContext null

        if (!force && isCacheFresh()) {
            val cached = getActiveBelarusVlessKey()
            if (!cached.isNullOrBlank()) {
                AppLogger.i(
                    TAG,
                    "Ключ Беларуси из кэша, возраст ${cacheAgeMinutes()} мин, панель не опрашивается"
                )
                return@withContext cached
            }
        } else if (force) {
            AppLogger.i(TAG, "Принудительное обновление ключа Беларуси у панели")
        } else {
            AppLogger.w(
                TAG,
                "Кэш подписки протух (${cacheAgeMinutes()} мин), ключ Беларуси обновляется у панели"
            )
        }

        val serverKey = fetchBelarusConfigFromServer()
        if (!serverKey.isNullOrBlank()) {
            markConfirmedAt()
            AppLogger.i(TAG, "Belarus key issued by belarus-config")
            return@withContext serverKey
        }

        AppLogger.w(TAG, "belarus-config недоступен, узел Беларусь не будет подключён")

        if (_currentUser.value == null) {
            AppLogger.w(TAG, "Сессия истекла, ключ Беларуси не будет взят из кэша")
            return@withContext null
        }

        val cachedKey = _subscription.value?.belarusVlessKey
            ?: prefs?.getString(KEY_SUB_BELARUS_VLESS_KEY, null)
        if (!cachedKey.isNullOrBlank()) {
            AppLogger.w(TAG, "Используется кэшированный ключ Беларуси с прошлого успешного входа")
            return@withContext cachedKey
        }

        AppLogger.w(TAG, "Нет верифицированного ключа Беларуси ни с сервера, ни из кэша")
        null
    }

    private suspend fun fetchBelarusConfigFromServer(): String? = withContext(Dispatchers.IO) {
        when (val result = fetchBelarusConfig()) {
            is BelarusConfigResult.Key -> {
                persistBelarusKey(result.vlessKey, result.clientUuid, result.paidUntil)
                result.vlessKey
            }
            is BelarusConfigResult.NoSubscription -> {
                AppLogger.i(TAG, "Сервер сообщил: активной подписки нет")
                null
            }
            is BelarusConfigResult.Unavailable -> {
                AppLogger.w(TAG, "belarus-config недоступен: ${result.reason}")
                null
            }
        }
    }

    private sealed class BelarusConfigResult {
        data class Key(
            val vlessKey: String,
            val clientUuid: String,
            val paidUntil: String?
        ) : BelarusConfigResult()
        object NoSubscription : BelarusConfigResult()
        data class Unavailable(val reason: String) : BelarusConfigResult()
    }

    private fun persistBelarusKey(key: String, clientUuid: String, paidUntil: String?) {
        val sub = _subscription.value
        if (sub != null) {
            _subscription.value = sub.copy(
                belarusVlessKey = key,
                belarusClientUuid = clientUuid,
                paidUntil = paidUntil ?: sub.paidUntil
            )
        }
        prefs?.edit()
            ?.putString(KEY_SUB_BELARUS_VLESS_KEY, key)
            ?.putString(KEY_SUB_BELARUS_CLIENT_UUID, clientUuid)
            ?.apply()
    }

    private suspend fun fetchBelarusConfig(): BelarusConfigResult = withContext(Dispatchers.IO) {
        try {
            val token = accessToken
            if (token.isNullOrEmpty()) {
                return@withContext BelarusConfigResult.Unavailable("нет сессии")
            }
            fun call(bearer: String): Response {
                val request = Request.Builder()
                    .url("${SupabaseConfig.SUPABASE_URL}/functions/v1/belarus-config")
                    .header("apikey", SupabaseConfig.getAnonKey())
                    .header("Authorization", "Bearer $bearer")
                    .get()
                    .build()
                return httpClient.newCall(request).execute()
            }

            var response = try {
                // Пул мог сохранить сокет от прошлого туннеля — он мёртв, но
                // запрос на нём висит до таймаута. Начинаем с чистого листа.
                resetHttpConnections()
                call(token)
            } catch (e: java.io.IOException) {
                AppLogger.w(TAG, "belarus-config: первая попытка оборвалась (${e.message}), повторяю")
                resetHttpConnections()
                call(token)
            }
            if (response.code == 401 && refreshAccessToken(token)) {
                val refreshed = accessToken
                if (refreshed.isNullOrEmpty()) {
                    return@withContext BelarusConfigResult.Unavailable("токен не обновился")
                }
                resetHttpConnections()
                response = call(refreshed)
            } else if (response.code == 401 && _currentUser.value == null) {
                return@withContext BelarusConfigResult.Unavailable("сессия истекла")
            }

            val body = response.body?.string().orEmpty()
            when {
                response.code == 403 -> BelarusConfigResult.NoSubscription
                !response.isSuccessful -> BelarusConfigResult.Unavailable("HTTP ${response.code}")
                else -> {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val key = json?.get("vlessKey")?.asString
                    if (key.isNullOrBlank()) {
                        BelarusConfigResult.Unavailable("в ответе нет ключа")
                    } else {
                        BelarusConfigResult.Key(
                            vlessKey = key,
                            clientUuid = json.get("clientUuid")?.asString.orEmpty(),
                            paidUntil = json.get("paidUntil")?.takeIf { !it.isJsonNull }?.asString
                        )
                    }
                }
            }
        } catch (e: Exception) {
            BelarusConfigResult.Unavailable(e.message ?: "сетевая ошибка")
        }
    }

    // ===================== Server-driven paid nodes =====================

    /**
     * Returns the cached VLESS key for a node from `app_config.paid_nodes`.
     *
     * The key is scoped to the signed-in account: after sign-out the cache is
     * cleared, and an empty session always yields null even if a stale key
     * somehow survived.
     */
    fun getNodeVlessKey(nodeId: String): String? {
        if (_currentUser.value == null) return null
        if (nodeId.isBlank()) return null
        val key = prefs?.getString(nodeCacheKey(nodeId), null)
        return key?.takeIf { it.startsWith("vless://") }
    }

    /**
     * Issues (or refreshes) a key for a generic VLESS node by calling the edge
     * function named in its `config_function`.
     *
     * France and Belarus keep their dedicated methods because they have
     * provider-specific fallbacks. Any new node added to `app_config` uses
     * this path, so no APK update is needed to support it.
     */
    suspend fun ensureNodeVlessKey(node: PaidNode, force: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            if (!hasActiveSubscription()) return@withContext null
            _currentUser.value ?: return@withContext null

            if (!force && isCacheFresh()) {
                val cached = getNodeVlessKey(node.id)
                if (!cached.isNullOrBlank()) {
                    AppLogger.i(
                        TAG,
                        "Ключ узла ${node.id} из кэша, возраст ${cacheAgeMinutes()} мин, панель не опрашивается"
                    )
                    return@withContext cached
                }
            } else if (force) {
                AppLogger.i(TAG, "Принудительное обновление ключа узла ${node.id}")
            }

            val key = fetchNodeConfig(node.configFunction)
            if (!key.isNullOrBlank()) {
                prefs?.edit()
                    ?.putString(nodeCacheKey(node.id), key)
                    ?.apply()
                markConfirmedAt()
                AppLogger.i(TAG, "Ключ узла ${node.id} выдан ${node.configFunction}")
                return@withContext key
            }

            if (_currentUser.value == null) {
                AppLogger.w(TAG, "Сессия истекла, ключ узла ${node.id} не будет взят из кэша")
                return@withContext null
            }

            val cached = getNodeVlessKey(node.id)
            if (!cached.isNullOrBlank()) {
                AppLogger.w(TAG, "Используется кэшированный ключ узла ${node.id} с прошлого входа")
                return@withContext cached
            }
            AppLogger.w(TAG, "Нет ключа узла ${node.id} ни с сервера, ни из кэша")
            null
        }

    private fun nodeCacheKey(nodeId: String): String =
        KEY_SUB_NODE_PREFIX + nodeId.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    private suspend fun fetchNodeConfig(configFunction: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val token = accessToken
                if (token.isNullOrEmpty()) return@withContext null
                fun call(bearer: String): Response {
                    val request = Request.Builder()
                        .url("${SupabaseConfig.SUPABASE_URL}/functions/v1/$configFunction")
                        .header("apikey", SupabaseConfig.getAnonKey())
                        .header("Authorization", "Bearer $bearer")
                        .get()
                        .build()
                    return httpClient.newCall(request).execute()
                }

                var response = try {
                    resetHttpConnections()
                    call(token)
                } catch (e: java.io.IOException) {
                    AppLogger.w(TAG, "$configFunction: первая попытка оборвалась (${e.message}), повторяю")
                    resetHttpConnections()
                    call(token)
                }
                if (response.code == 401 && refreshAccessToken(token)) {
                    val refreshed = accessToken
                    if (refreshed.isNullOrEmpty()) return@withContext null
                    resetHttpConnections()
                    response = call(refreshed)
                }

                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "$configFunction: HTTP ${response.code}")
                    return@withContext null
                }
                val json = gson.fromJson(body, JsonObject::class.java)
                val key = json?.get("vlessKey")?.asString
                if (key.isNullOrBlank()) {
                    AppLogger.w(TAG, "$configFunction: в ответе нет ключа")
                    return@withContext null
                }
                key
            } catch (e: Exception) {
                AppLogger.w(TAG, "$configFunction: ${e.message ?: "сетевая ошибка"}")
                null
            }
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
        val flow = sub?.flow
        if (!flow.isNullOrBlank()) return flow

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
