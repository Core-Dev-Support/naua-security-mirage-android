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
        protectDatagram: ((java.net.DatagramSocket) -> Unit)? = null,
        protectSocket: ((Socket) -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        val count = samples.coerceAtLeast(1)
        val probes = if (count == 1) {
            listOf(probeOnce(server, timeoutMs, protectSocket, protectDatagram))
        } else {
            coroutineScope {
                (1..count)
                    .map { async { probeOnce(server, timeoutMs, protectSocket, protectDatagram) } }
                    .awaitAll()
            }
        }
        val collected = probes.filterNotNull()
        val result = if (collected.isEmpty()) 9999L else collected.sorted()[collected.size / 2]
        server.pingMs = result
        if (result in 1..9998) {
            Log.d(TAG, "Server ${server.tag} ping: ${result}ms from ${collected.size}/$count probes")
        } else {
            Log.w(TAG, "Server ${server.tag} unreachable")
        }
        result
    }

    suspend fun measureAllPings(
        servers: List<VlessServer>,
        protectDatagram: ((java.net.DatagramSocket) -> Unit)? = null,
        protectSocket: ((Socket) -> Unit)? = null,
        probeTimeoutMs: Int = QUALITY_PROBE_TIMEOUT_MS,
        totalBudgetMs: Long = 3000L,
        samples: Int = 1
    ): List<VlessServer> = coroutineScope {
        kotlinx.coroutines.withTimeoutOrNull(totalBudgetMs) {
            servers.map { server ->
                async(Dispatchers.IO) {
                    measureQuality(
                        server,
                        protectSocket = protectSocket,
                        protectDatagram = protectDatagram,
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
        protectSocket: ((Socket) -> Unit)?,
        protectDatagram: ((java.net.DatagramSocket) -> Unit)? = null
    ): Long? = kotlinx.coroutines.withTimeoutOrNull(timeoutMs.toLong() + 1500L) {
        val target = resolveAddress(server.address) ?: return@withTimeoutOrNull null

        val socketProtector: (Socket) -> Unit = protectSocket ?: { s ->
            com.naua_security_mirage.app.vpn.MirageVpnService.protectSocket(s)
        }
        val datagramProtector: (java.net.DatagramSocket) -> Unit = protectDatagram ?: { ds ->
            com.naua_security_mirage.app.vpn.MirageVpnService.protectDatagram(ds)
        }

        if (server.isHysteria2) {
            // Узел работает на Hysteria 2 (UDP/QUIC): опрашиваем через QUIC UDP probe
            var datagramSocket: java.net.DatagramSocket? = null
            try {
                datagramSocket = java.net.DatagramSocket()
                datagramProtector(datagramSocket)
                datagramSocket.soTimeout = timeoutMs.coerceAtLeast(400)
                val start = System.currentTimeMillis()
                datagramSocket.connect(InetSocketAddress(target, server.port))
                // QUIC Initial datagram MUST be >= 1200 bytes per RFC 9000 (shorter packets are dropped by quic-go)
                val quicProbe = ByteArray(1200)
                quicProbe[0] = 0xc0.toByte()
                quicProbe[1] = 0x1a.toByte()
                quicProbe[2] = 0x2a.toByte()
                quicProbe[3] = 0x3a.toByte()
                quicProbe[4] = 0x4a.toByte()
                quicProbe[5] = 0x08.toByte()
                for (i in 1..8) { quicProbe[5 + i] = i.toByte() }
                val packet = java.net.DatagramPacket(quicProbe, quicProbe.size, target, server.port)
                datagramSocket.send(packet)
                val buffer = ByteArray(1500)
                val responsePacket = java.net.DatagramPacket(buffer, buffer.size)
                datagramSocket.receive(responsePacket)
                val duration = System.currentTimeMillis() - start
                if (duration > 0) return@withTimeoutOrNull duration
            } catch (_: Exception) {
                // Если UDP-ответ не получен (обфускация salamander или UDP-фильтрация),
                // проверяем задержку до того же сервера через TCP (порт 443)
            } finally {
                try {
                    datagramSocket?.close()
                } catch (_: Throwable) {}
            }

            val tcpPort = if (server.port != 443) 443 else server.port
            var socket: Socket? = null
            try {
                socket = Socket()
                socketProtector(socket)
                val start = System.currentTimeMillis()
                socket.connect(InetSocketAddress(target, tcpPort), timeoutMs)
                val duration = System.currentTimeMillis() - start
                return@withTimeoutOrNull if (duration > 0) duration else 1L
            } catch (_: Exception) {
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {}
            }
            return@withTimeoutOrNull null
        }

        // Попытка TCP connect (для VLESS Reality, XHTTP, TCP)
        var socket: Socket? = null
        try {
            socket = Socket()
            socketProtector(socket)
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(target, server.port), timeoutMs)
            val duration = System.currentTimeMillis() - start
            return@withTimeoutOrNull if (duration > 0) duration else 1L
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {}
        }
    }

    private fun isIpAddress(address: String): Boolean {
        val normalized = address.trim().removePrefix("[").removeSuffix("]")
        if (normalized.isEmpty()) return false
        val octets = normalized.split('.')
        if (octets.size == 4 && octets.all { (it.toIntOrNull() ?: -1) in 0..255 }) return true
        return normalized.contains(":") && normalized.count { it == ':' } >= 2
    }

    private suspend fun resolveAddress(host: String): InetAddress? {
        val key = host.trim()
        if (key.isEmpty()) return null
        if (isIpAddress(key)) {
            return try { InetAddress.getByName(key) } catch (_: Exception) { null }
        }
        val now = System.currentTimeMillis()
        val cached = addressCache[key]
        if (cached != null && now - cached.at < RESOLVE_TTL_MS) {
            return try { InetAddress.getByName(cached.ip) } catch (_: Exception) { null }
        }
        val resolved = try {
            try {
                val all = InetAddress.getAllByName(key)
                // На Wi-Fi часто включён IPv6, но маршруты к зарубежным серверам дропаются ТСПУ/РКН.
                // Приоритет IPv4 полностью устраняет 3-5 секундные зависания сокета на Wi-Fi.
                all.firstOrNull { it is java.net.Inet4Address } ?: all.firstOrNull()
            } catch (_: Exception) {
                com.naua_security_mirage.app.data.supabase.SupabaseConfig
                    .resolveHostDoh(key).firstOrNull { it is java.net.Inet4Address }
                    ?: com.naua_security_mirage.app.data.supabase.SupabaseConfig.resolveHostDoh(key).firstOrNull()
            }
        } catch (_: Exception) {
            null
        } ?: return null

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
        protectDatagram: ((java.net.DatagramSocket) -> Unit)? = null,
        probeTimeoutMs: Int = QUALITY_PROBE_TIMEOUT_MS,
        samples: Int = QUALITY_SAMPLES
    ) {
        val qualitySamples = mutableListOf<Long>()
        repeat(samples) {
            val duration = probeOnce(server, probeTimeoutMs, protectSocket, protectDatagram)
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

    fun selectBestServer(servers: List<VlessServer>): VlessServer? {
        if (servers.isEmpty()) return null
        val reachable = servers.filter { it.pingMs in 1..9998 }
        if (reachable.isEmpty()) return servers.firstOrNull()

        val best = reachable.minByOrNull { score(it) } ?: servers.firstOrNull()
        if (reachable.size > 1 && best != null) {
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
