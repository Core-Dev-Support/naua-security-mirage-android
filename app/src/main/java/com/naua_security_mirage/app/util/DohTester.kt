package com.naua_security_mirage.app.util

import com.naua_security_mirage.app.data.model.DnsServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class DnsProbeResult(
    val server: DnsServer,
    val reachable: Boolean,
    val latencyMs: Long,
    val answered: Boolean
)

object DohTester {

    private const val PROBE_NAME = "example.com"
    private const val PROBE_TIMEOUT_MS = 6000L
    private const val SOCKS_HOST = "127.0.0.1"
    private const val SOCKS_PORT = 10808

    fun buildClient(throughTunnel: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
        if (throughTunnel) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(SOCKS_HOST, SOCKS_PORT)))
        }
        return builder.build()
    }

    suspend fun probe(server: DnsServer, throughTunnel: Boolean): DnsProbeResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val client = buildClient(throughTunnel)
            try {
                val separator = if (server.url.contains('?')) "&" else "?"
                val url = server.url + separator + "name=" + PROBE_NAME + "&type=A"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", "MirageVPN")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val elapsed = System.currentTimeMillis() - started
                    val answered = response.isSuccessful && body.contains("\"Answer\"")
                    DnsProbeResult(
                        server = server,
                        reachable = true,
                        latencyMs = elapsed.coerceAtLeast(1L),
                        answered = answered
                    )
                }
            } catch (_: Throwable) {
                DnsProbeResult(
                    server = server,
                    reachable = false,
                    latencyMs = System.currentTimeMillis() - started,
                    answered = false
                )
            }
        }
}
