package com.naua_security_mirage.app.data.supabase

import com.naua_security_mirage.app.BuildConfig
import com.naua_security_mirage.app.data.model.VlessServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Supabase gateway не закрывает HTTP/2-поток, а системный DNS на мобильных
 * сетях часто не отвечает. Ходим по HTTP/1.1; при отказе системного
 * резолвера пробуем DoH.
 */
fun OkHttpClient.Builder.forSupabase(): OkHttpClient.Builder =
    protocols(listOf(Protocol.HTTP_1_1))
        .dns(supabaseDns)
        // Оператор молча убивает простаивающие NAT-сессии, и повторное
        // использование такого соединения висит до таймаута.
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .addInterceptor { chain ->
            // Один из адресов Cloudflare иногда молчит до таймаута.
            // Повторяем только чтение: POST-refresh нельзя дублировать.
            try {
                chain.proceed(chain.request())
            } catch (e: java.io.IOException) {
                val retryable = e is java.net.SocketTimeoutException || e is java.net.ConnectException
                if (retryable && chain.request().method == "GET") {
                    chain.proceed(chain.request())
                } else {
                    throw e
                }
            }
        }

private const val SUPABASE_FALLBACK_HOST = "orwwaptbxmwxvfwfqavc.supabase.co"

private val fallbackAddresses: List<InetAddress> by lazy {
    // shortcut: запасные IP Supabase могут смениться; обновить при смене проекта.
    listOf("8.47.69.8", "8.6.112.8").map { InetAddress.getByName(it) }
}

private val supabaseDns: Dns by lazy {
    val dohClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    val providers = listOf(
        DnsOverHttps.Builder()
            .client(dohClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1")
            )
            .build(),
        DnsOverHttps.Builder()
            .client(dohClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4")
            )
            .build()
    )
    object : Dns {
        private val nextAddress = java.util.concurrent.atomic.AtomicInteger()

        override fun lookup(hostname: String): List<InetAddress> {
            // Для Supabase сразу берём проверенные A-записи: системный DNS
            // оператора может вернуть недоступный адрес, а не только NXDOMAIN.
            // Порядок чередуем, чтобы повтор запроса шёл на другой адрес.
            if (hostname == SUPABASE_FALLBACK_HOST) {
                val shift = nextAddress.getAndIncrement() % fallbackAddresses.size
                return fallbackAddresses.drop(shift) + fallbackAddresses.take(shift)
            }
            try {
                val system = Dns.SYSTEM.lookup(hostname)
                if (system.isNotEmpty()) return system
            } catch (_: Exception) {
            }
            for (provider in providers) {
                try {
                    val addresses = provider.lookup(hostname)
                    if (addresses.isNotEmpty()) return addresses
                } catch (_: Exception) {
                }
            }
            throw java.net.UnknownHostException(hostname)
        }
    }
}

object SupabaseConfig {
    val SUPABASE_URL: String get() = BuildConfig.SUPABASE_URL
    val SUPABASE_ANON_KEY: String get() = BuildConfig.SUPABASE_ANON_KEY

    fun getAnonKey(): String {
        val envKey = System.getenv("SUPABASE_ANON_KEY")
        if (!envKey.isNullOrBlank()) return envKey

        if (BuildConfig.DEBUG) {
            val keyFile = File("supabase_key.txt")
            if (keyFile.exists()) {
                val fileKey = keyFile.readText().trim()
                if (fileKey.isNotEmpty()) return fileKey
            }
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

    val BELARUS_LABEL = "Беларусь (Платный)"

    var yooMoneyWallet: String = BuildConfig.YOOMONEY_WALLET
    var subscriptionPriceRub: Int = 50

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
