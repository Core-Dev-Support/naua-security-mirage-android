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

    val FRANCE_HOST: String get() = BuildConfig.FRANCE_HOST
    val FRANCE_PORT: Int get() = if (BuildConfig.FRANCE_PORT > 0) BuildConfig.FRANCE_PORT else 443
    val FRANCE_PBK: String get() = BuildConfig.FRANCE_PBK
    val FRANCE_SNI: String get() = BuildConfig.FRANCE_SNI
    val FRANCE_SID: String get() = BuildConfig.FRANCE_SID
    val FRANCE_SPX: String get() = BuildConfig.FRANCE_SPX.ifEmpty { "/" }
    val FRANCE_FINGERPRINT: String get() = BuildConfig.FRANCE_FINGERPRINT.ifEmpty { "chrome" }
    val USE_LIBXRAY_CONVERTER: Boolean get() = BuildConfig.USE_LIBXRAY_CONVERTER

    val FRANCE_ENDPOINTS: String get() = BuildConfig.FRANCE_ENDPOINTS

    var yooMoneyWallet: String = BuildConfig.YOOMONEY_WALLET
    var subscriptionPriceRub: Int = 30

    data class Endpoint(val host: String, val port: Int)

    fun franceEndpoints(): List<Endpoint> {
        val parsed = FRANCE_ENDPOINTS
            .split(',', ';', ' ', '\n')
            .mapNotNull { raw ->
                val entry = raw.trim()
                if (entry.isEmpty()) return@mapNotNull null
                val separator = entry.lastIndexOf(':')
                if (separator <= 0) {
                    Endpoint(entry, FRANCE_PORT)
                } else {
                    val port = entry.substring(separator + 1).trim().toIntOrNull() ?: return@mapNotNull null
                    Endpoint(entry.substring(0, separator).trim(), port)
                }
            }
            .filter { it.host.isNotBlank() && it.port in 1..65535 }

        return (listOf(Endpoint(FRANCE_HOST, FRANCE_PORT)) + parsed)
            .distinctBy { "${it.host}:${it.port}" }
    }

    fun getFranceServers(uuid: String? = null, flow: String? = null): List<VlessServer> {
        val clientUuid = uuid?.takeIf { it.isNotBlank() } ?: SupabaseManager.instance.getActiveClientUuid()

        val finalUuid = clientUuid
        val clientFlow = flow ?: SupabaseManager.instance.getActiveClientFlow(finalUuid)
        return franceEndpoints().mapIndexed { index, endpoint ->
            VlessServer(
                id = "france-premium",
                tag = if (index == 0) "Франция (Платный)" else "Франция (Платный) ${endpoint.host}",
                address = endpoint.host,
                port = endpoint.port,
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

    fun getFranceServer(uuid: String? = null, flow: String? = null): VlessServer {
        return getFranceServers(uuid, flow).first()
    }
}
