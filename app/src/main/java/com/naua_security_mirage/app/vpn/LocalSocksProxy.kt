package com.naua_security_mirage.app.vpn

import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom
import javax.net.SocketFactory

/**
 * Параметры локального SOCKS-входа Xray.
 *
 * Раньше вход слушал фиксированный 127.0.0.1:10808 без пароля. Любое
 * приложение на устройстве могло подключиться к нему и гонять трафик через
 * VPN пользователя: обходить per-app split tunneling, тратить платный трафик
 * и использовать его exit-IP. Теперь порт и логин/пароль генерируются заново
 * на каждый запуск ядра, а приложение-клиент знает их только в памяти.
 */
object LocalProxyConfig {
    private const val MIN_PORT = 20_000
    private const val MAX_PORT = 60_000
    private const val ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    private val random = SecureRandom()

    @Volatile
    var port: Int = 10_808
        private set

    @Volatile
    var username: String = ""
        private set

    @Volatile
    var password: String = ""
        private set

    val authEnabled: Boolean
        get() = username.isNotEmpty() && password.isNotEmpty()

    fun rotate() {
        port = MIN_PORT + random.nextInt(MAX_PORT - MIN_PORT)
        username = token(12)
        password = token(24)
    }

    private fun token(length: Int): String {
        val chars = CharArray(length)
        for (i in chars.indices) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)]
        }
        return String(chars)
    }
}

/**
 * Минимальный SOCKS5-клиент с поддержкой username/password.
 *
 * Java-реализация SOCKS в OkHttp не умеет парольную авторизацию, поэтому
 * рукопожатие делаем сами: приложение всё равно ходит только к локальному
 * Xray, а не к произвольным SOCKS-серверам.
 */
object Socks5Client {

    fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", LocalProxyConfig.port), timeoutMs)
            socket.soTimeout = timeoutMs
            handshake(socket, host, port)
            return socket
        } catch (t: Throwable) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    fun handshake(socket: Socket, host: String, port: Int) {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        if (LocalProxyConfig.authEnabled) {
            output.write(byteArrayOf(0x05, 0x01, 0x02))
            output.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting, 2)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x02.toByte()) {
                throw IOException("SOCKS5 auth method rejected")
            }

            val user = LocalProxyConfig.username.toByteArray(Charsets.US_ASCII)
            val pass = LocalProxyConfig.password.toByteArray(Charsets.US_ASCII)
            if (user.size > 255 || pass.size > 255) {
                throw IOException("SOCKS5 credentials too long")
            }
            output.write(0x01)
            output.write(user.size)
            output.write(user)
            output.write(pass.size)
            output.write(pass)
            output.flush()

            val authReply = ByteArray(2)
            readFully(input, authReply, 2)
            if (authReply[0] != 0x01.toByte() || authReply[1] != 0x00.toByte()) {
                throw IOException("SOCKS5 auth failed")
            }
        } else {
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting, 2)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                throw IOException("SOCKS5 no-auth rejected")
            }
        }

        val ipv4 = parseIpv4(host)
        val request = if (ipv4 != null) {
            ByteArray(10).apply {
                this[0] = 0x05
                this[1] = 0x01
                this[2] = 0x00
                this[3] = 0x01
                ipv4.copyInto(this, 4)
                this[8] = ((port shr 8) and 0xFF).toByte()
                this[9] = (port and 0xFF).toByte()
            }
        } else {
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            if (hostBytes.isEmpty() || hostBytes.size > 255) {
                throw IOException("SOCKS5 host is too long")
            }
            ByteArray(7 + hostBytes.size).apply {
                this[0] = 0x05
                this[1] = 0x01
                this[2] = 0x00
                this[3] = 0x03
                this[4] = hostBytes.size.toByte()
                hostBytes.copyInto(this, 5)
                val offset = 5 + hostBytes.size
                this[offset] = ((port shr 8) and 0xFF).toByte()
                this[offset + 1] = (port and 0xFF).toByte()
            }
        }
        output.write(request)
        output.flush()

        val header = ByteArray(4)
        readFully(input, header, 4)
        if (header[0] != 0x05.toByte() || header[1] != 0x00.toByte()) {
            throw IOException("SOCKS5 connect failed: ${header[1].toInt() and 0xFF}")
        }
        val addressLength = when (header[3].toInt()) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> {
                val length = ByteArray(1)
                readFully(input, length, 1)
                length[0].toInt() and 0xFF
            }
            else -> throw IOException("SOCKS5 bad address type")
        }
        readFully(input, ByteArray(addressLength + 2), addressLength + 2)
    }

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read <= 0) throw EOFException("SOCKS stream ended early")
            offset += read
        }
    }
}

/**
 * Socket, который при connect() сам поднимает SOCKS5-туннель через
 * локальный Xray. Нужен, чтобы OkHttp (DohTester) мог ходить через туннель
 * с парольной авторизацией: стандартный java.net.Proxy её не поддерживает.
 */
class Socks5Socket : Socket() {
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress
            ?: throw IOException("Unsupported SOCKS target")
        val host = target.hostString ?: target.address?.hostAddress
            ?: throw IOException("Unknown SOCKS target")
        super.connect(InetSocketAddress("127.0.0.1", LocalProxyConfig.port), timeout)
        soTimeout = timeout
        Socks5Client.handshake(this, host, target.port)
    }
}

class Socks5SocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socks5Socket()

    override fun createSocket(host: String, port: Int): Socket =
        Socks5Socket().apply { connect(InetSocketAddress(host, port), 5_000) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket(host.hostAddress ?: throw IOException("Unknown host"), port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = createSocket(address, port)
}
