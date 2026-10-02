package com.naua_security_mirage.app.data.repository

import android.util.Log
import com.naua_security_mirage.app.data.model.VlessServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class PingRepository {

    suspend fun measurePing(
        server: VlessServer,
        timeoutMs: Int = 1500,
        protectSocket: ((Socket) -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        val calculatedPing = kotlinx.coroutines.withTimeoutOrNull(timeoutMs.toLong() + 500L) {
            var socket: Socket? = null
            try {
                val start = System.currentTimeMillis()
                socket = Socket()
                protectSocket?.invoke(socket)
                socket.connect(InetSocketAddress(server.address, server.port), timeoutMs)
                val duration = System.currentTimeMillis() - start
                val result = if (duration > 0) duration else 1L
                server.pingMs = result
                Log.d(TAG, "Server ${server.tag} ping: ${result}ms")
                result
            } catch (e: Exception) {
                Log.w(TAG, "Server ${server.tag} unreachable: ${e.message}")
                server.pingMs = 9999L
                9999L
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
            }
        } ?: 9999L

        if (server.pingMs <= 0 || server.pingMs > 9998) {
            server.pingMs = calculatedPing
        }
        calculatedPing
    }

    suspend fun measureAllPings(
        servers: List<VlessServer>,
        protectSocket: ((Socket) -> Unit)? = null
    ): List<VlessServer> = coroutineScope {
        kotlinx.coroutines.withTimeoutOrNull(4500L) {
            servers.map { server ->
                async(Dispatchers.IO) {
                    measureQuality(server, protectSocket = protectSocket)
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
        var socket: Socket? = null
        try {
            val start = System.currentTimeMillis()
            socket = Socket()
            protectSocket?.invoke(socket)
            socket.connect(InetSocketAddress(server.address, server.port), timeoutMs)
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

    private suspend fun measureQuality(
        server: VlessServer,
        protectSocket: ((Socket) -> Unit)?
    ) {
        val samples = mutableListOf<Long>()
        repeat(QUALITY_SAMPLES) {
            val duration = probeOnce(server, QUALITY_PROBE_TIMEOUT_MS, protectSocket)
            if (duration != null) samples.add(duration)
        }

        server.lossPercent = ((QUALITY_SAMPLES - samples.size) * 100) / QUALITY_SAMPLES

        if (samples.isEmpty()) {
            server.pingMs = 9999L
            server.jitterMs = -1L
            Log.w(TAG, "Server ${server.tag} unreachable in $QUALITY_SAMPLES probes")
            return
        }

        server.pingMs = samples.sorted()[samples.size / 2]
        server.jitterMs = if (samples.size < 2) {
            0L
        } else {
            samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average().toLong()
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
    }
}
