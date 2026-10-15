package com.naua_security_mirage.app.util

import com.naua_security_mirage.app.data.model.DnsServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ThreadLocalRandom
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

    const val PROBE_NAME = "example.com"
    private const val PROBE_TYPE_A = 1
    private const val PROBE_TIMEOUT_MS = 2500L
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
        val id = ThreadLocalRandom.current().nextInt(0x10000)
        out.write(id shr 8)
        out.write(id and 0xFF)
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
            result.answered -> "${result.latencyMs} мс, ${result.dnsStatus}, записей ${result.answerCount}"
            result.dnsStatus == "REFUSED" -> "отказал (REFUSED)"
            result.dnsStatus == "SERVFAIL" -> "не смог разрешить (SERVFAIL)"
            result.dnsStatus == "FORMERR" -> "сервер не понял запрос (FORMERR)"
            result.dnsStatus == "NXDOMAIN" -> "NXDOMAIN"
            else -> "ответил, записей нет (${result.dnsStatus})"
        }
        return "${result.server.id} ${result.server.url} -> $verdict${result.detail}"
    }

    suspend fun pickFastest(
        servers: List<DnsServer>,
        throughTunnel: Boolean = false,
        budgetMs: Long = 1500L
    ): DnsProbeResult? = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext null
        val results = kotlinx.coroutines.coroutineScope {
            withTimeoutOrNull(budgetMs) {
                servers.map { server -> async { probe(server, throughTunnel) } }.awaitAll()
            }
        } ?: return@withContext null
        results.filter { it.answered }.minByOrNull { it.latencyMs }
    }

    suspend fun probe(server: DnsServer, throughTunnel: Boolean): DnsProbeResult {
        val posted = probeOnce(server, throughTunnel, Form.POST)
        if (posted.answered || !posted.reachable) return posted
        val fetched = probeOnce(server, throughTunnel, Form.GET)
        if (fetched.answered) return fetched
        server.jsonUrl?.let { jsonUrl ->
            val viaJson = probeJson(server, jsonUrl, throughTunnel)
            if (viaJson.answered) return viaJson
        }
        return posted
    }

    private enum class Form { POST, GET, JSON }

    private fun probeJson(
        server: DnsServer,
        jsonUrl: String,
        throughTunnel: Boolean
    ): DnsProbeResult {
        val started = System.currentTimeMillis()
        val client = buildClient(throughTunnel)
        return try {
            val separator = if (jsonUrl.contains('?')) "&" else "?"
            val url = jsonUrl + separator + "name=" + PROBE_NAME + "&type=A"
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
                    answered = answered,
                    httpStatus = response.code,
                    dnsStatus = "JSON",
                    answerCount = if (answered) 1 else 0,
                    detail = ", тело ${body.length} байт"
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
                detail = ", JSON, ошибка ${t.javaClass.simpleName}"
            )
        }
    }

    private fun probeOnce(
        server: DnsServer,
        throughTunnel: Boolean,
        form: Form
    ): DnsProbeResult {
        val started = System.currentTimeMillis()
        val client = buildClient(throughTunnel)
        return try {
            val query = buildQuery(PROBE_NAME, PROBE_TYPE_A)
            val builder = Request.Builder()
                .header("Accept", "application/dns-message")
                .header("User-Agent", "MirageVPN")
            when (form) {
                Form.GET -> {
                    val encoded = android.util.Base64.encodeToString(
                        query,
                        android.util.Base64.URL_SAFE or
                                android.util.Base64.NO_PADDING or
                                android.util.Base64.NO_WRAP
                    )
                    val separator = if (server.url.contains('?')) "&" else "?"
                    builder.url(server.url + separator + "dns=" + encoded).get()
                }
                else -> builder.url(server.url)
                    .post(query.toRequestBody("application/dns-message".toMediaType()))
            }
            client.newCall(builder.build()).execute().use { response ->
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
                    detail = ", ${form.name}, тело ${raw.size} байт" +
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
                detail = ", ${form.name}, ошибка ${t.javaClass.simpleName}"
            )
        }
    }
}
