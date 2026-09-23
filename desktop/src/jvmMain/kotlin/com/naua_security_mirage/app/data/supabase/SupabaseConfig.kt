package com.naua_security_mirage.app.data.supabase

import java.io.File

object SupabaseConfig {
    val SUPABASE_URL: String get() = System.getenv("SUPABASE_URL") ?: ""
    val SUPABASE_ANON_KEY: String get() = System.getenv("SUPABASE_ANON_KEY") ?: ""

    fun getAnonKey(): String {
        val envKey = System.getenv("SUPABASE_ANON_KEY")
        if (!envKey.isNullOrBlank()) return envKey

        val keyFile = File("supabase_key.txt")
        if (keyFile.exists()) {
            val fileKey = keyFile.readText().trim()
            if (fileKey.isNotEmpty()) return fileKey
        }

        return SUPABASE_ANON_KEY
    }

    // 3X-UI Panel Configuration
    val THREE_X_UI_BASE_URL: String get() = System.getenv("THREE_X_UI_BASE_URL") ?: ""
    val THREE_X_UI_USERNAME: String get() = System.getenv("THREE_X_UI_USERNAME") ?: ""
    val THREE_X_UI_PASSWORD: String get() = System.getenv("THREE_X_UI_PASSWORD") ?: ""
    val THREE_X_UI_INBOUND_ID: Int get() = System.getenv("THREE_X_UI_INBOUND_ID")?.toIntOrNull() ?: 1

    // Reality VLESS Parameters for France Server
    val FRANCE_HOST: String get() = System.getenv("FRANCE_HOST") ?: ""
    val FRANCE_PORT: Int get() = System.getenv("FRANCE_PORT")?.toIntOrNull() ?: 443
    val FRANCE_PBK: String get() = System.getenv("FRANCE_PBK") ?: ""
    val FRANCE_SNI: String get() = System.getenv("FRANCE_SNI") ?: ""
    val FRANCE_SID: String get() = System.getenv("FRANCE_SID") ?: ""
    val FRANCE_SPX: String get() = System.getenv("FRANCE_SPX") ?: "/"
    val FRANCE_FINGERPRINT: String get() = System.getenv("FRANCE_FINGERPRINT") ?: "chrome"
    val FRANCE_DEFAULT_UUID: String get() = System.getenv("FRANCE_DEFAULT_UUID") ?: ""

    // YooMoney Payment Configuration
    var yooMoneyWallet: String = System.getenv("YOOMONEY_WALLET") ?: ""
    var subscriptionPriceRub: Int = 30
}
