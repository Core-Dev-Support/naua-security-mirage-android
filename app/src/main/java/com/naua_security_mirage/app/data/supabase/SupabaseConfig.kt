package com.naua_security_mirage.app.data.supabase

import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.data.model.VlessServer
import java.io.File

object SupabaseConfig {
    val SUPABASE_URL: String get() = BuildConfig.SUPABASE_URL
    val SUPABASE_ANON_KEY: String get() = BuildConfig.SUPABASE_ANON_KEY

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

    // 3X-UI Panel Configuration (Injected via BuildConfig from Secrets)
    val THREE_X_UI_BASE_URL: String get() = BuildConfig.THREE_X_UI_BASE_URL
    val THREE_X_UI_USERNAME: String get() = BuildConfig.THREE_X_UI_USERNAME
    val THREE_X_UI_PASSWORD: String get() = BuildConfig.THREE_X_UI_PASSWORD
    val THREE_X_UI_INBOUND_ID: Int get() = if (BuildConfig.THREE_X_UI_INBOUND_ID > 0) BuildConfig.THREE_X_UI_INBOUND_ID else 2

    // Reality VLESS Parameters for France Server (Injected via BuildConfig from Secrets)
    val FRANCE_HOST: String get() = BuildConfig.FRANCE_HOST
    val FRANCE_PORT: Int get() = if (BuildConfig.FRANCE_PORT > 0) BuildConfig.FRANCE_PORT else 443
    val FRANCE_PBK: String get() = BuildConfig.FRANCE_PBK
    val FRANCE_SNI: String get() = BuildConfig.FRANCE_SNI
    val FRANCE_SID: String get() = BuildConfig.FRANCE_SID
    val FRANCE_SPX: String get() = BuildConfig.FRANCE_SPX.ifEmpty { "/" }
    val FRANCE_FINGERPRINT: String get() = BuildConfig.FRANCE_FINGERPRINT.ifEmpty { "chrome" }

    val FRANCE_DEFAULT_UUID: String get() = BuildConfig.FRANCE_DEFAULT_UUID
    val USE_LIBXRAY_CONVERTER: Boolean get() = BuildConfig.USE_LIBXRAY_CONVERTER

    // YooMoney Payment Configuration
    var yooMoneyWallet: String = BuildConfig.YOOMONEY_WALLET
    var subscriptionPriceRub: Int = 30

    fun getFranceServer(uuid: String? = null, flow: String? = null): VlessServer {
        val clientUuid = uuid?.ifEmpty { SupabaseManager.instance.getActiveClientUuid() }
            ?: SupabaseManager.instance.getActiveClientUuid()
        val finalUuid = clientUuid.ifEmpty { FRANCE_DEFAULT_UUID }
        val clientFlow = flow ?: SupabaseManager.instance.getActiveClientFlow(finalUuid)
        return VlessServer(
            id = "france-premium",
            tag = "Франция (Платный)",
            address = FRANCE_HOST,
            port = FRANCE_PORT,
            uuid = finalUuid,
            network = "tcp",
            security = "reality",
            publicKey = FRANCE_PBK,
            fingerprint = FRANCE_FINGERPRINT,
            serverName = FRANCE_SNI,
            host = FRANCE_SNI,
            mode = "none",
            path = FRANCE_SPX,
            shortId = FRANCE_SID,
            flow = clientFlow
        )
    }
}
