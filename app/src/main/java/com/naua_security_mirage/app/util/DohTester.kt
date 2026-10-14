package com.naua_security_mirage.app.util

import android.util.Base64
import com.naua_security_mirage.app.data.model.DnsServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class DnsProbeResult(
    val server: DnsServer,
    val reachable: Boolean,
    val latencyMs: Long,
    val answered: Boolean,
    val httpStatus: Int,
    val dnsStatus: String,
    val answerCount: Int,
    val detail: String
)

object DohTester {

    private const val PROBE_NAME = "example.com"
    private const val PROBE_TYPE_A = 1
    private const val PROBE_TIMEOUT_MS = 6000L
    private const val SOCKS_HOST = "127.0.0.1"
    private const val SOCKS_PORT = 10808
    private const val CLASS_IN = 1

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

    private fun buildQuery(name: String, type: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write((System.nanoTime() and 0xFFFF).toInt())
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        out.write(0x00)
        out.write(type)
        out.write(0x00)
        out.write(CLASS_IN)
        return out.toByteArray()
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun decodeBase64Url(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )

    private fun parseResponse(bytes: ByteArray): Pair<String, Int> {
        if (bytes.size < 12) return "короткий ответ" to -1
        val flags = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val rcode = flags and 0x000F
        val answers = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        val name = when (rcode) {
            0 -> "NOERROR"
            1 -> "FORMERR"
            2 -> "SERVFAIL"
            3 -> "NXDOMAIN"
            4 -> "NOTIMP"
            5 -> "REFUSED"
            else -> "код $rcode"
        }
        return name to answers
    }

    fun logLine(result: DnsProbeResult): String {
        val verdict = when {
            !result.reachable -> "не отвечает"
            result.httpStatus != 200 -> "HTTP ${result.httpStatus}, это не DNS-ответ"
            !result.answered -> "ответил, записей нет (${result.dnsStatus})"
            else -> "${result.latencyMs} мс, ${result.dnsStatus}, записей ${result.answerCount}"
        }
        return "${result.server.id} ${result.server.url} -> $verdict${result.detail}"
    }

    suspend fun probe(server: DnsServer, throughTunnel: Boolean): DnsProbeResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val client = buildClient(throughTunnel)
            try {
                val query = base64Url(buildQuery(PROBE_NAME, PROBE_TYPE_A))
                val separator = if (server.url.contains('?')) "&" else "?"
                val request = Request.Builder()
                    .url(server.url + separator + "dns=" + query)
                    .header("Accept", "application/dns-message")
                    .header("User-Agent", "MirageVPN")
                    .build()
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.bytes() ?: ByteArray(0)
                    val elapsed = System.currentTimeMillis() - started
                    val (dnsStatus, answers) = parseResponse(raw)
                    val answered = response.isSuccessful && raw.size >= 12 && answers > 0
                    DnsProbeResult(
                        server = server,
                        reachable = true,
                        latencyMs = elapsed.coerceAtLeast(1L),
                        answered = answered,
                        httpStatus = response.code,
                        dnsStatus = dnsStatus,
                        answerCount = answers,
                        detail = ", тело ${raw.size} байт" +
                                (if (raw.size in 1..24) ", начало " + raw.take(16).joinToString(" ") { "%02x".format(it) } else "")
                    )
                }
            } catch (t: Throwable) {
                DnsProbeResult(
                    server = server,
                    reachable = false,
                    latencyMs = System.currentTimeMillis() - started,
                    answered = false,
                    httpStatus = -1,
                    dnsStatus = "-",
                    answerCount = -1,
                    detail = ", ошибка ${t.javaClass.simpleName}"
                )
            }
        }
}
