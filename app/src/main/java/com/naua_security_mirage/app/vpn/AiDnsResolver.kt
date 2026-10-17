package com.naua_security_mirage.app.vpn

import com.naua_security_mirage.app.util.AppLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object AiDnsResolver {

    private const val TAG = "AiDnsResolver"
    private const val DOT_PORT = 853
    private const val PLAIN_PORT = 53
    private const val CONNECT_TIMEOUT_MS = 2500
    private const val READ_TIMEOUT_MS = 1200
    private const val RESPONSE_BUDGET_MS = 4500L
    private const val UDP_TIMEOUT_MS = 900
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private var cachedServer: String? = null
    private var cachedAt = 0L
    private var cachedResult: Map<String, List<String>> = emptyMap()

    @Synchronized
    fun resolve(server: String, hosts: List<String>): Map<String, List<String>> {
        val name = server.trim().lowercase()
        val names = hosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (name.isEmpty() || names.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        if (name == cachedServer && cachedResult.isNotEmpty() && now - cachedAt < CACHE_TTL_MS) {
            AppLogger.i(TAG, "DNS $name: недавний ответ ещё свежий, имён: ${cachedResult.size}")
            return cachedResult
        }

        val started = System.currentTimeMillis()
        val result = if (isIpLiteral(name)) resolvePlain(name, names) else resolveDot(name, names)
        if (result.isNotEmpty()) {
            cachedServer = name
            cachedAt = now
            cachedResult = result
        }
        AppLogger.i(
            TAG,
            "DNS $name: адреса получены для ${result.size} имён из ${names.size} " +
                    "за ${System.currentTimeMillis() - started} мс"
        )
        return result
    }

    fun isIpLiteral(value: String): Boolean = IPV4.matches(value.trim())

    private fun resolveDot(host: String, names: List<String>): Map<String, List<String>> {
        val addresses = try {
            InetAddress.getAllByName(host).sortedByDescending { it is Inet4Address }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "DNS $host: адрес сервера не разрешился (${t.javaClass.simpleName})")
            return emptyMap()
        }
        for (address in addresses) {
            val outcome = tryDot(host, address, names)
            if (outcome.isNotEmpty()) return outcome
        }
        return emptyMap()
    }

    private fun tryDot(host: String, address: InetAddress, names: List<String>): Map<String, List<String>> {
        var raw: Socket? = null
        var tls: SSLSocket? = null
        return try {
            val socket = Socket()
            raw = socket
            socket.connect(InetSocketAddress(address, DOT_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, host, DOT_PORT, true) as SSLSocket
            tls = ssl
            ssl.soTimeout = READ_TIMEOUT_MS
            try {
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
            } catch (_: Throwable) {
            }
            ssl.startHandshake()

            val output = ssl.outputStream
            val input = ssl.inputStream
            val pending = LinkedHashMap<Int, String>()
            val answers = LinkedHashMap<String, List<String>>()
            names.forEachIndexed { index, name ->
                val id = (index + 1) and 0xFFFF
                pending[id] = name
                val query = buildQuery(id, name)
                output.write(query.size shr 8 and 0xFF)
                output.write(query.size and 0xFF)
                output.write(query)
            }
            output.flush()

            val deadline = System.currentTimeMillis() + RESPONSE_BUDGET_MS
            while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
                val message = try {
                    readMessage(input)
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: Throwable) {
                    break
                } ?: break
                if (message.size < 12) continue
                val id = ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
                val name = pending.remove(id) ?: continue
                val ips = parseAddresses(message)
                if (ips.isNotEmpty()) answers[name] = ips
            }
            answers
        } catch (t: Throwable) {
            AppLogger.w(TAG, "DNS $host через ${address.hostAddress}: ${t.javaClass.simpleName}")
            emptyMap()
        } finally {
            try {
                tls?.close()
            } catch (_: Throwable) {
            }
            try {
                raw?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolvePlain(serverIp: String, names: List<String>): Map<String, List<String>> {
        val answers = LinkedHashMap<String, List<String>>()
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = UDP_TIMEOUT_MS
                val target = InetAddress.getByName(serverIp)
                names.forEachIndexed { index, name ->
                    val query = buildQuery((index + 1) and 0xFFFF, name)
                    try {
                        socket.send(DatagramPacket(query, query.size, target, PLAIN_PORT))
                        val buffer = ByteArray(4096)
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val ips = parseAddresses(packet.data.copyOf(packet.length))
                        if (ips.isNotEmpty()) answers[name] = ips
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
            answers
        } catch (t: Throwable) {
            AppLogger.w(TAG, "DNS $serverIp: ${t.javaClass.simpleName}")
            emptyMap()
        }
    }

    private fun readMessage(input: InputStream): ByteArray? {
        val header = ByteArray(2)
        var read = 0
        while (read < 2) {
            val got = input.read(header, read, 2 - read)
            if (got < 0) return null
            read += got
        }
        val length = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        if (length < 12) return null
        val message = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val got = input.read(message, offset, length - offset)
            if (got < 0) return null
            offset += got
        }
        return message
    }

    private fun buildQuery(id: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8 and 0xFF)
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
        out.write(0x01)
        out.write(0x00)
        out.write(0x01)
        return out.toByteArray()
    }

    private fun parseAddresses(message: ByteArray): List<String> {
        if (message.size < 12) return emptyList()
        val questions = ((message[4].toInt() and 0xFF) shl 8) or (message[5].toInt() and 0xFF)
        val answers = ((message[6].toInt() and 0xFF) shl 8) or (message[7].toInt() and 0xFF)
        var position = 12
        repeat(questions) {
            position = skipName(message, position) ?: return emptyList()
            position += 4
            if (position > message.size) return emptyList()
        }
        val out = ArrayList<String>()
        repeat(answers) {
            position = skipName(message, position) ?: return out
            if (position + 10 > message.size) return out
            val type = ((message[position].toInt() and 0xFF) shl 8) or (message[position + 1].toInt() and 0xFF)
            val length = ((message[position + 8].toInt() and 0xFF) shl 8) or (message[position + 9].toInt() and 0xFF)
            if (type == 1 && length == 4 && position + 14 <= message.size) {
                out += "${message[position + 10].toInt() and 0xFF}." +
                        "${message[position + 11].toInt() and 0xFF}." +
                        "${message[position + 12].toInt() and 0xFF}." +
                        "${message[position + 13].toInt() and 0xFF}"
            }
            position += 10 + length
        }
        return out
    }

    private fun skipName(message: ByteArray, start: Int): Int? {
        var position = start
        while (position < message.size) {
            val length = message[position].toInt() and 0xFF
            if (length == 0) return position + 1
            if (length and 0xC0 == 0xC0) return position + 2
            position += 1 + length
        }
        return null
    }
}
