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

    suspend fun measureAllPings(servers: List<VlessServer>): List<VlessServer> = coroutineScope {
        kotlinx.coroutines.withTimeoutOrNull(3000L) {
            servers.map { server ->
                async(Dispatchers.IO) {
                    measurePing(server, timeoutMs = 1500)
                    server
                }
            }.awaitAll()
        } ?: servers
    }

    fun selectBestServer(servers: List<VlessServer>): VlessServer {
        val reachable = servers.filter { it.pingMs in 1..9998 }
        return if (reachable.isNotEmpty()) {
            reachable.minByOrNull { it.pingMs } ?: servers.first()
        } else {
            servers.first()
        }
    }

    companion object {
        private const val TAG = "PingRepository"
    }
}
