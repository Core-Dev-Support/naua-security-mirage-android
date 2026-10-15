package com.naua_security_mirage.app.data.repository

import android.util.Log
import com.naua_security_mirage.app.data.model.VlessServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class PingRepository {

    suspend fun measurePing(
        server: VlessServer,
        timeoutMs: Int = 1500,
        samples: Int = 1,
        protectSocket: ((Socket) -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        probeOnce(server, timeoutMs, protectSocket)
        val collected = ArrayList<Long>(samples)
        repeat(samples.coerceAtLeast(1)) {
            probeOnce(server, timeoutMs, protectSocket)?.let { value -> collected.add(value) }
        }
        val result = if (collected.isEmpty()) 9999L else collected.sorted()[collected.size / 2]
        server.pingMs = result
        if (result in 1..9998) {
            Log.d(TAG, "Server ${server.tag} ping: ${result}ms from ${collected.size}/$samples probes")
        } else {
            Log.w(TAG, "Server ${server.tag} unreachable")
        }
        result
    }

    suspend fun measureAllPings(
        servers: List<VlessServer>,
        protectSocket: ((Socket) -> Unit)? = null,
        probeTimeoutMs: Int = QUALITY_PROBE_TIMEOUT_MS,
        totalBudgetMs: Long = 6000L,
        samples: Int = QUALITY_SAMPLES
    ): List<VlessServer> = coroutineScope {
        kotlinx.coroutines.withTimeoutOrNull(totalBudgetMs) {
            servers.map { server ->
                async(Dispatchers.IO) {
                    measureQuality(
                        server,
                        protectSocket = protectSocket,
                        probeTimeoutMs = probeTimeoutMs,
                        samples = samples
                    )
                    server
                }
            }.awaitAll()
        } ?: servers
    }

    private suspend fun probeOnce(
        server: VlessServer,
        timeoutMs: Int,
        protectSocket: ((Socket) -> Unit)?
    ): Long? = kotlinx.coroutines.withTimeoutOrNull(timeoutMs.toLong() + 500L) {
        val target = try {
            resolveAddress(server.address)
        } catch (_: Exception) {
            null
        } ?: return@withTimeoutOrNull null
        var socket: Socket? = null
        try {
            socket = Socket()
            protectSocket?.invoke(socket)
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(target, server.port), timeoutMs)
            val duration = System.currentTimeMillis() - start
            if (duration > 0) duration else 1L
        } catch (e: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    private fun resolveAddress(host: String): InetAddress? {
        val key = host.trim()
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()
        val cached = addressCache[key]
        if (cached != null && now - cached.at < RESOLVE_TTL_MS) {
            return InetAddress.getByName(cached.ip)
        }
        val resolved = InetAddress.getByName(key)
        val literal = resolved.hostAddress
        if (literal != null) {
            if (addressCache.size > 64) addressCache.clear()
            addressCache[key] = CachedAddress(literal, now)
        }
        return resolved
    }

    private data class CachedAddress(val ip: String, val at: Long)

    private suspend fun measureQuality(
        server: VlessServer,
        protectSocket: ((Socket) -> Unit)?,
        probeTimeoutMs: Int = QUALITY_PROBE_TIMEOUT_MS,
        samples: Int = QUALITY_SAMPLES
    ) {
        probeOnce(server, probeTimeoutMs, protectSocket)
        val qualitySamples = mutableListOf<Long>()
        repeat(samples) {
            val duration = probeOnce(server, probeTimeoutMs, protectSocket)
            if (duration != null) qualitySamples.add(duration)
        }

        server.lossPercent = ((samples - qualitySamples.size) * 100) / samples

        if (qualitySamples.isEmpty()) {
            server.pingMs = 9999L
            server.jitterMs = -1L
            Log.w(TAG, "Server ${server.tag} unreachable in $samples probes")
            return
        }

        server.pingMs = qualitySamples.sorted()[qualitySamples.size / 2]
        server.jitterMs = if (qualitySamples.size < 2) {
            0L
        } else {
            qualitySamples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average().toLong()
        }
        Log.d(
            TAG,
            "Server ${server.tag} median=${server.pingMs}ms loss=${server.lossPercent}% jitter=${server.jitterMs}ms"
        )
    }

    fun selectBestServer(servers: List<VlessServer>): VlessServer {
        val reachable = servers.filter { it.pingMs in 1..9998 }
        if (reachable.isEmpty()) return servers.first()

        val best = reachable.minByOrNull { score(it) } ?: servers.first()
        if (reachable.size > 1) {
            Log.d(
                TAG,
                "Chose ${best.tag} (${best.pingMs}ms loss=${best.lossPercent}%) out of ${reachable.size}"
            )
        }
        return best
    }

    private fun score(server: VlessServer): Double {
        val loss = if (server.lossPercent in 0..100) server.lossPercent else 0
        val jitter = if (server.jitterMs > 0) server.jitterMs else 0L
        return server.pingMs + (loss * LOSS_PENALTY_MS_PER_PERCENT) + jitter
    }

    companion object {
        private const val TAG = "PingRepository"
        private const val QUALITY_SAMPLES = 3
        private const val QUALITY_PROBE_TIMEOUT_MS = 700
        private const val LOSS_PENALTY_MS_PER_PERCENT = 5.0
        private const val RESOLVE_TTL_MS = 10 * 60 * 1000L

        private val addressCache =
            java.util.concurrent.ConcurrentHashMap<String, CachedAddress>()
    }
}
