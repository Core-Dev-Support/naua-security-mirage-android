package com.naua_security_mirage.app.data.supabase

import java.io.File
import java.util.Properties

object SupabaseConfig {
    private val props: Properties by lazy {
        val p = Properties()
        try {
            SupabaseConfig::class.java.classLoader?.getResourceAsStream("mirage.properties")?.use { stream ->
                p.load(stream)
            }
        } catch (_: Exception) {}
        p
    }

    private fun getVal(key: String, envKey: String = key): String {
        val env = System.getenv(envKey)
        if (!env.isNullOrBlank()) return env.trim()
        val prop = props.getProperty(key)
        if (!prop.isNullOrBlank()) return prop.trim()
        return ""
    }

    val SUPABASE_URL: String get() = getVal("supabaseUrl", "SUPABASE_URL")
    val SUPABASE_ANON_KEY: String get() = getVal("supabaseAnonKey", "SUPABASE_ANON_KEY")

    fun getAnonKey(): String {
        val key = SUPABASE_ANON_KEY
        if (key.isNotEmpty()) return key

        val keyFile = File("supabase_key.txt")
        if (keyFile.exists()) {
            val fileKey = keyFile.readText().trim()
            if (fileKey.isNotEmpty()) return fileKey
        }
        return ""
    }

    val THREE_X_UI_BASE_URL: String get() = System.getenv("THREE_X_UI_BASE_URL") ?: ""
    val THREE_X_UI_USERNAME: String get() = System.getenv("THREE_X_UI_USERNAME") ?: ""
    val THREE_X_UI_PASSWORD: String get() = System.getenv("THREE_X_UI_PASSWORD") ?: ""
    val THREE_X_UI_INBOUND_ID: Int get() = System.getenv("THREE_X_UI_INBOUND_ID")?.toIntOrNull() ?: 2

    val FRANCE_HOST: String get() = getVal("franceHost", "FRANCE_HOST")
    val FRANCE_PORT: Int get() = getVal("francePort", "FRANCE_PORT").toIntOrNull() ?: 443
    val FRANCE_PBK: String get() = getVal("francePbk", "FRANCE_PBK")
    val FRANCE_SNI: String get() = getVal("franceSni", "FRANCE_SNI")
    val FRANCE_SID: String get() = getVal("franceSid", "FRANCE_SID")
    val FRANCE_SPX: String get() = getVal("franceSpx", "FRANCE_SPX").ifEmpty { "/" }
    val FRANCE_FINGERPRINT: String get() = getVal("franceFingerprint", "FRANCE_FINGERPRINT").ifEmpty { "chrome" }
    val FRANCE_DEFAULT_UUID: String get() = getVal("franceDefaultUuid", "FRANCE_DEFAULT_UUID")
    val FRANCE_ENDPOINTS: String get() = getVal("franceEndpoints", "FRANCE_ENDPOINTS")

    var yooMoneyWallet: String
        get() = getVal("yooMoneyWallet", "YOOMONEY_WALLET")
        set(_) {}
    var subscriptionPriceRub: Int = 50
}
