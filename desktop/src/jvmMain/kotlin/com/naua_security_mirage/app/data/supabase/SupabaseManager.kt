package com.naua_security_mirage.app.data.supabase

import com.naua_security_mirage.app.util.AppLogger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.user.UserInfo
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.awt.Desktop
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class SubscriptionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("email") val email: String,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("plan") val plan: String = "free",
    @SerialName("paid_until") val paidUntil: String? = null,
    @SerialName("vless_key") val vlessKey: String? = null
)

class SupabaseManager {
    companion object {
        private const val TAG = "SupabaseManager"
        val instance by lazy { SupabaseManager() }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = SupabaseConfig.SUPABASE_URL,
        supabaseKey = SupabaseConfig.getAnonKey()
    ) {
        install(Auth)
        install(Postgrest)
    }

    private val _currentUser = MutableStateFlow<UserInfo?>(null)
    val currentUser: StateFlow<UserInfo?> = _currentUser.asStateFlow()

    private val _subscription = MutableStateFlow<SubscriptionDto?>(null)
    val subscription: StateFlow<SubscriptionDto?> = _subscription.asStateFlow()

    init {
        // Observe current user auth state
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

    suspend fun signInWithEmail(emailInput: String, passwordInput: String): Result<Unit> {
        return try {
            client.auth.signInWith(Email) {
                email = emailInput.trim()
                password = passwordInput
            }
            val user = client.auth.currentUserOrNull()
            _currentUser.value = user
            refreshSubscription()
            AppLogger.info(TAG, "User signed in: ${user?.email}")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Sign in failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun signUpWithEmail(emailInput: String, passwordInput: String): Result<Unit> {
        return try {
            client.auth.signUpWith(Email) {
                email = emailInput.trim()
                password = passwordInput
            }
            val user = client.auth.currentUserOrNull()
            _currentUser.value = user
            refreshSubscription()
            AppLogger.info(TAG, "User signed up: ${user?.email}")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Sign up failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogle(): Result<Unit> {
        return try {
            // For desktop Compose, launch system browser with Supabase OAuth URL
            client.auth.signInWith(Google)
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
                // If record doesn't exist yet, insert default free entry
                val defaultSub = SubscriptionDto(
                    userId = user.id,
                    email = user.email.orEmpty(),
                    isActive = false,
                    plan = "free"
                )
                try {
                    client.from("subscriptions").insert(defaultSub)
                    _subscription.value = defaultSub
                } catch (_: Exception) {
                    _subscription.value = defaultSub
                }
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to refresh subscription: ${e.message}")
        }
    }

    /**
     * Activates or ensures a dedicated France VLESS key exists for the user.
     */
    suspend fun ensureFranceVlessKey(): String? {
        val sub = _subscription.value
        if (sub?.vlessKey != null && sub.vlessKey.isNotBlank()) {
            return sub.vlessKey
        }

        val user = _currentUser.value ?: return null
        // Generate or retrieve from 3X-UI panel
        val generatedKey = ThreeXUiService.getOrCreateClient(user.email.orEmpty(), user.id)
        if (generatedKey != null) {
            try {
                val updated = (sub ?: SubscriptionDto(
                    userId = user.id,
                    email = user.email.orEmpty(),
                    isActive = true,
                    plan = "premium"
                )).copy(vlessKey = generatedKey, isActive = true)

                client.from("subscriptions").upsert(updated)
                _subscription.value = updated
            } catch (e: Exception) {
                AppLogger.error(TAG, "Error saving generated key to Supabase: ${e.message}")
            }
        }
        return generatedKey
    }

    /**
     * Checks if current user has an active, non-expired subscription.
     */
    fun hasActiveSubscription(): Boolean {
        val sub = _subscription.value ?: return false
        if (!sub.isActive) return false
        val paidUntilStr = sub.paidUntil ?: return true // If active without date, consider active
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val date = sdf.parse(paidUntilStr.take(19)) ?: return true
            date.after(Date())
        } catch (_: Exception) {
            true
        }
    }
}
