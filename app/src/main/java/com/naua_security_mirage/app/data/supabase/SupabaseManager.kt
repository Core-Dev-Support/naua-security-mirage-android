package com.naua_security_mirage.app.data.supabase

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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
        private const val PREFS_NAME = "mirage_supabase_auth"
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
            ?.apply()
    }

    // === Per-email subscription cache (survives signOut) ===
    // Each email gets a separate SharedPreferences key with full subscription data.
    // This ensures switching accounts never destroys another account's subscription.

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

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedToken = prefs?.getString(KEY_ACCESS_TOKEN, null)
            val savedRefreshToken = prefs?.getString(KEY_REFRESH_TOKEN, null)
            val savedId = prefs?.getString(KEY_USER_ID, null)
            val savedEmail = prefs?.getString(KEY_USER_EMAIL, null)

            if (!savedToken.isNullOrEmpty() && !savedId.isNullOrEmpty() && !savedEmail.isNullOrEmpty()) {
                accessToken = savedToken
                refreshToken = savedRefreshToken
                _currentUser.value = SupabaseUser(savedId, savedEmail)

                // Load subscription from per-email cache — instant, no network needed
                val cached = loadEmailCache(savedEmail)
                if (cached != null) {
                    _subscription.value = cached
                    AppLogger.info(TAG, "Init: subscription loaded from email cache for $savedEmail")
                } else {
                    // Fallback: try old-style prefs (migration path)
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
                            clientUuid = prefs?.getString(KEY_SUB_CLIENT_UUID, null) ?: extractUuidFromVless(prefs?.getString(KEY_SUB_VLESS_KEY, null))
                        )
                        _subscription.value = sub
                        // Migrate to email cache
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

            // Load subscription from per-email cache instantly (no network needed)
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
                // Load from email cache (new signup won't have one, but handles re-registration)
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
            val oauthUrl = "${SupabaseConfig.SUPABASE_URL}/auth/v1/authorize?provider=google&redirect_to=$redirectUri"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(oauthUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Google sign in error: ${e.message}")
        }
    }

    suspend fun handleOAuthCallback(uri: Uri): Result<SupabaseUser> = withContext(Dispatchers.IO) {
        try {
            val params = mutableMapOf<String, String>()
            // Parse fragment (#access_token=...&refresh_token=...)
            uri.fragment?.split("&")?.forEach { part ->
                val pair = part.split("=")
                if (pair.size >= 2) {
                    params[pair[0]] = Uri.decode(pair.subList(1, pair.size).joinToString("="))
                }
            }
            // Parse query (?access_token=...)
            uri.queryParameterNames.forEach { key ->
                uri.getQueryParameter(key)?.let { params[key] = it }
            }

            val token = params["access_token"]
            val oauthRefreshToken = params["refresh_token"]

            if (token.isNullOrEmpty()) {
                val error = params["error_description"] ?: params["error"] ?: "Токен авторизации не получен"
                return@withContext Result.failure(Exception(error))
            }

            val user = fetchUserFromToken(token) ?: run {
                // Fallback decode payload from JWT
                val parts = token.split(".")
                if (parts.size >= 2) {
                    val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
                    val obj = gson.fromJson(payload, JsonObject::class.java)
                    val uid = obj.get("sub")?.asString.orEmpty()
                    val email = obj.get("email")?.asString.orEmpty()
                    SupabaseUser(uid.ifEmpty { "google-user" }, email.ifEmpty { "user@google.com" })
                } else {
                    SupabaseUser("google-user", "Пользователь Google")
                }
            }

            accessToken = token
            refreshToken = oauthRefreshToken
            _currentUser.value = user
            _subscription.value = loadEmailCache(user.email)

            prefs?.edit()
                ?.putString(KEY_ACCESS_TOKEN, token)
                ?.putString(KEY_REFRESH_TOKEN, oauthRefreshToken)
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

    private suspend fun fetchUserFromToken(token: String): SupabaseUser? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${SupabaseConfig.SUPABASE_URL}/auth/v1/user")
                .header("apikey", SupabaseConfig.getAnonKey())
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val bodyStr = response.body?.string().orEmpty()
            val userObj = gson.fromJson(bodyStr, JsonObject::class.java)
            val userId = userObj?.get("id")?.asString.orEmpty()
            val userEmail = userObj?.get("email")?.asString.orEmpty()
            if (userId.isNotEmpty()) SupabaseUser(userId, userEmail) else null
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to fetch user from token: ${e.message}")
            null
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
        // NOTE: per-email subscription caches are NOT cleared here.
        // They persist so that logging back into the same account restores the subscription instantly.
        AppLogger.info(TAG, "User signed out (email subscription caches preserved)")
    }

    suspend fun activatePremiumSubscription(): Boolean = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext false
        try {
            val vlessKey = ThreeXUiService.getOrCreateClient(user.email, user.id)
                ?: return@withContext false
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

            // Subscription writes are service-role/webhook-only. The local
            // cache is safe to keep, but a client-side POST would now be
            // rejected by RLS and must not be treated as durable provisioning.
            AppLogger.w(TAG, "Client-side subscription sync is disabled; webhook provisioning is required")

            AppLogger.info(TAG, "Premium subscription activated locally for ${user.email}, clientUuid=$clientUuid")
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to activate premium subscription: ${e.message}")
            false
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
            // Older production tables do not have flow yet; the main query is
            // deliberately compatible with those installations.
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
            // 1. Check 3X-UI (primary source of truth)
            var threeXUiReached = false
            var subFrom3XUi: SubscriptionDto? = null
            val cachedSub = _subscription.value ?: loadEmailCache(user.email)
            val knownClientUuid = cachedSub?.clientUuid
                ?: extractUuidFromVless(cachedSub?.vlessKey)
                ?: user.id
            try {
                subFrom3XUi = ThreeXUiService.checkSubscription(user.email, knownClientUuid)
                threeXUiReached = true  // Only a fully parsed 3X-UI response is authoritative
            } catch (e: Exception) {
                AppLogger.w(TAG, "3X-UI unavailable during refresh: ${e.message}")
            }

            if (subFrom3XUi != null) {
                // Found active subscription on 3X-UI server
                val realClientUuid = subFrom3XUi.clientUuid ?: extractUuidFromVless(subFrom3XUi.vlessKey)
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
                AppLogger.info(TAG, "Subscription confirmed from 3X-UI: clientUuid=$realClientUuid, until=${fullSub.paidUntil}")
                return@withContext
            }

            // 2. Try Supabase as the entitlement source of truth if 3X-UI fails or returns null
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
                            // Sub exists in Supabase. If 3X-UI reached but client missing, restore it!
                            if (threeXUiReached && subFrom3XUi == null) {
                                AppLogger.info(TAG, "Restoring missing 3X-UI client from Supabase data...")
                                val restoredKey = ThreeXUiService.getOrCreateClient(user.email, clientUuid)
                                val finalSub = restoredKey?.let { fullSub.copy(vlessKey = it) } ?: fullSub
                                _subscription.value = finalSub
                                saveEmailCache(user.email, finalSub)
                                prefs?.edit()
                                    ?.putString(KEY_SUB_USER_ID, user.id)
                                    ?.putString(KEY_SUB_EMAIL, user.email)
                                    ?.putBoolean(KEY_SUB_IS_ACTIVE, true)
                                    ?.putString(KEY_SUB_PLAN, finalSub.plan)
                                    ?.putString(KEY_SUB_PAID_UNTIL, finalSub.paidUntil)
                                    ?.putString(KEY_SUB_VLESS_KEY, finalSub.vlessKey)
                                    ?.putString(KEY_SUB_CLIENT_UUID, clientUuid)
                                    ?.apply()
                                AppLogger.info(TAG, "Subscription restored from Supabase: clientUuid=$clientUuid, keyRestored=${restoredKey != null}")
                                return@withContext
                            } else {
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
                                AppLogger.info(TAG, "Subscription loaded from Supabase: clientUuid=$clientUuid, until=${fullSub.paidUntil}")
                                return@withContext
                            }
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
                    threeXUiAuthoritative = threeXUiReached
                )
            ) {
                // Only an authoritative Supabase response may clear a paid cache.
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

            // 3. Both 3X-UI and Supabase failed/returned nothing — keep cached subscription
            // The email cache was already loaded during login, so _subscription.value may be set.
            // If not, try loading it now as a last resort.
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
            // On unexpected error, keep whatever is already cached — never destroy subscription data
        }
    }

    private fun hasDateNotExpired(dateStr: String?): Boolean {
        return SubscriptionPolicy.isActive(true, dateStr)
    }

    suspend fun ensureFranceVlessKey(): String? = withContext(Dispatchers.IO) {
        if (!hasActiveSubscription()) return@withContext null
        val user = _currentUser.value ?: return@withContext null
        val sub = _subscription.value
        if (!sub?.vlessKey.isNullOrBlank()) {
            val extracted = extractUuidFromVless(sub?.vlessKey)
            if (extracted != null && sub?.clientUuid == null) {
                val updatedSub = sub?.copy(clientUuid = extracted)
                _subscription.value = updatedSub
                if (updatedSub != null) saveEmailCache(user.email, updatedSub)
                prefs?.edit()?.putString(KEY_SUB_CLIENT_UUID, extracted)?.apply()
            }
            return@withContext sub?.vlessKey
        }

        val generatedKey = ThreeXUiService.getOrCreateClient(
            user.email,
            sub?.clientUuid ?: extractUuidFromVless(sub?.vlessKey) ?: user.id
        )
        if (generatedKey != null) {
            val clientUuid = extractUuidFromVless(generatedKey) ?: user.id
            val updated = (sub ?: SubscriptionDto(
                    userId = user.id,
                    email = user.email,
                    isActive = true,
                    plan = "premium"
                )).copy(vlessKey = generatedKey, isActive = true, clientUuid = clientUuid)

                _subscription.value = updated
                saveEmailCache(user.email, updated)

                prefs?.edit()
                    ?.putString(KEY_SUB_USER_ID, user.id)
                    ?.putString(KEY_SUB_EMAIL, user.email)
                    ?.putBoolean(KEY_SUB_IS_ACTIVE, true)
                    ?.putString(KEY_SUB_PLAN, "premium")
                    ?.putString(KEY_SUB_PAID_UNTIL, updated.paidUntil)
                    ?.putString(KEY_SUB_VLESS_KEY, generatedKey)
                    ?.putString(KEY_SUB_CLIENT_UUID, clientUuid)
                    ?.apply()

                // The database is service-role/webhook-only. Keep the verified
                // key locally and let the entitlement remain retryable.
                AppLogger.w(TAG, "France key kept in local cache; Supabase writes are webhook-only")
        }
        generatedKey
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

        return SupabaseConfig.FRANCE_DEFAULT_UUID
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
        if (targetUuid.equals(SupabaseConfig.FRANCE_DEFAULT_UUID, ignoreCase = true)) {
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
}
