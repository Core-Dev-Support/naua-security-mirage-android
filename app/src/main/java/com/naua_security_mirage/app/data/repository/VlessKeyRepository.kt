package com.naua_security_mirage.app.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class VlessKeyRepository(
    private val context: Context,
    private val deviceIdRepository: DeviceIdRepository
) {

    private val httpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    @Volatile
    var lastFetchPanelReachable: Boolean = false
        private set

    private fun getCertDigest(): ByteArray {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo ?: return ByteArray(32)
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
            if (signatures.isNullOrEmpty()) return ByteArray(32)
            MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())
        } catch (_: Throwable) {
            ByteArray(32)
        }
    }

    private fun deriveKey(): ByteArray {
        val cert = getCertDigest()
        val derived = ByteArray(32)
        for (i in 0 until 32) {
            derived[i] = (BASE_SEED[i].toInt() xor cert[i].toInt()).toByte()
        }
        return derived
    }

    private fun mask(data: ByteArray): String {
        val key = deriveKey()
        val res = ByteArray(data.size)
        for (i in data.indices) {
            res[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return String(res, Charsets.UTF_8)
    }

    private val apiUrl: String by lazy { decoded(ENC_API_URL) }

    private fun decoded(blob: ByteArray): String = mask(blob).also {
        AppLogger.registerSecrets(it)
    }

    suspend fun getVlessServers(forceRefresh: Boolean = false): List<VlessServer> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<VlessServer>()
        lastFetchPanelReachable = false

        val freshFromCache = mutableListOf<VlessServer>()
        if (!forceRefresh && isCacheFresh()) {
            parseCachedLiveConfig(freshFromCache)
        }
        if (freshFromCache.isNotEmpty()) {
            AppLogger.i(
                TAG,
                "Список бесплатных узлов из кэша, возраст ${cacheAgeMinutes()} мин, панель не опрашивается"
            )
            return@withContext freshFromCache.take(3)
        }

        val deviceId = deviceIdRepository.getOrCreateDeviceId()

        var definitiveEmpty = false
        repeat(2) { attempt ->
            if (servers.isNotEmpty() || definitiveEmpty) return@repeat
            kotlinx.coroutines.withTimeoutOrNull(9000) {
            try {
                val apiHost = try {
                    java.net.URI(apiUrl).host
                } catch (_: Throwable) {
                    null
                }

                AppLogger.i(TAG, "Free API: ${if (apiHost != null) "endpoint resolved" else "endpoint unparseable"}")
                val payload = JsonObject().apply {
                    addProperty("deviceId", deviceId)
                }
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                    .header("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val respBody = response.body?.string()
                    if (!respBody.isNullOrEmpty()) {
                        lastFetchPanelReachable = true
                        val root = JsonParser.parseString(respBody).asJsonObject
                        val responseConfig = root.getAsJsonObject("config") ?: root
                        val outboundCount = responseConfig.getAsJsonArray("outbounds")?.size() ?: -1
                        val sharedUserId = extractUuid(root.get("v2rayKey")?.asString)
                        val before = servers.size
                        parseOutbounds(responseConfig, servers, sharedUserId)
                        val parsed = servers.size - before
                        AppLogger.i(TAG, "Free API: записей в ответе: $outboundCount, разобрано узлов: $parsed")
                        if (servers.isNotEmpty()) {
                            cacheLiveConfig(respBody)
                        } else {
                            definitiveEmpty = true
                        }
                    } else {
                        AppLogger.w(TAG, "Free API: пустое тело ответа")
                        definitiveEmpty = true
                    }
                } else {
                    AppLogger.w(TAG, "Free API: код ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch servers from API: ${e.message}")
            }
            Unit
            }
            if (servers.isEmpty() && !definitiveEmpty && attempt == 0) {
                Log.w(TAG, "Повторный запрос списка узлов после паузы")
                delay(800)
            }
        }

        AppLogger.i(TAG, "Free API parsed nodes=${servers.size}")

        if (servers.isEmpty()) {
            parseCachedLiveConfig(servers)
        }
        if (servers.isEmpty()) {
            AppLogger.w(TAG, "Панель не вернула ни одного узла, встроенных запасных узлов больше нет")
        }

        val result = servers.take(3)
        result.forEach { server ->
            AppLogger.i(
                TAG,
                "Free candidate ${server.tag}: ${server.address}:${server.port}, " +
                    "network=${server.network}, security=${server.security}, " +
                    "mode=${server.mode}, path_present=${server.path.isNotBlank()}, " +
                    "path_query=${server.path.contains('?')}"
            )
        }
        result
    }

    private fun parseOutbounds(
        root: JsonObject,
        servers: MutableList<VlessServer>,
        sharedUserId: String? = null
    ) {
        val outbounds = root.getAsJsonArray("outbounds") ?: return
        for (elem in outbounds) {
            try {
                val obj = elem.asJsonObject
            val tag = obj.get("tag")?.asString ?: continue
            val protocol = obj.get("protocol")?.asString ?: continue

            if (protocol != "vless") continue

            val settings = obj.getAsJsonObject("settings") ?: continue
            val vnextArray = settings.getAsJsonArray("vnext") ?: continue
            if (vnextArray.size() == 0) continue

            val vnext = vnextArray[0].asJsonObject
            val address = vnext.get("address")?.asString ?: continue
            val port = vnext.get("port")?.asInt ?: 443
            val users = vnext.getAsJsonArray("users")
            val user = if (users != null && users.size() > 0) {
                users[0].asJsonObject
            } else if (!sharedUserId.isNullOrBlank()) {
                JsonObject().apply {
                    addProperty("id", sharedUserId)
                    addProperty("encryption", "none")
                }
            } else {
                continue
            }
            val uuid = user.get("id")?.asString?.takeIf { it.isNotBlank() } ?: continue
            val flow = user.get("flow")?.asString.orEmpty()

            val streamSettings = obj.getAsJsonObject("streamSettings") ?: continue
            val rawNetwork = streamSettings.get("network")?.asString ?: "tcp"
            val network = if (rawNetwork.equals("splithttp", ignoreCase = true)) "xhttp" else rawNetwork
            val security = streamSettings.get("security")?.asString ?: "none"

            var publicKey = ""
            var fingerprint = "chrome"
            var serverName = ""
            var shortId = ""
            var spiderX = "/"

            if (security.equals("reality", ignoreCase = true)) {
                val realitySettings = streamSettings.getAsJsonObject("realitySettings")
                if (realitySettings != null) {
                    publicKey = realitySettings.get("publicKey")?.asString.orEmpty()
                    fingerprint = realitySettings.get("fingerprint")?.asString ?: "chrome"
                    serverName = realitySettings.get("serverName")?.asString
                        ?: realitySettings.getAsJsonArray("serverNames")?.firstOrNull()?.asString
                        ?: ""
                    shortId = realitySettings.get("shortId")?.asString
                        ?: realitySettings.getAsJsonArray("shortIds")?.firstOrNull()?.asString
                        ?: ""
                    spiderX = realitySettings.get("spiderX")?.asString ?: "/"
                }
            }

            var path = ""
            var host = ""
            var mode = ""
            if (network.equals("xhttp", ignoreCase = true)) {
                val xhttpSettings = streamSettings.getAsJsonObject("xhttpSettings")
                if (xhttpSettings != null) {
                    path = xhttpSettings.get("path")?.asString ?: ""
                    val hostElement = xhttpSettings.get("host")
                    host = when {
                        hostElement == null -> ""
                        hostElement.isJsonArray -> hostElement.asJsonArray.firstOrNull()?.asString.orEmpty()
                        else -> hostElement.asString
                    }
                    mode = xhttpSettings.get("mode")?.asString ?: "packet-up"
                }
            } else if (network.equals("tcp", ignoreCase = true) && security.equals("reality", ignoreCase = true)) {
                path = spiderX
            }

            if (address.isBlank() || uuid.isBlank() ||
                (security.equals("reality", ignoreCase = true) &&
                    (publicKey.isBlank() || serverName.isBlank()))) {
                continue
            }

            val parsedServer = VlessServer(
                id = tag,
                tag = "Mirage Server #${servers.size + 1}",
                address = address,
                port = port,
                uuid = uuid,
                network = network,
                security = security,
                publicKey = publicKey,
                fingerprint = fingerprint,
                serverName = serverName,
                host = host,
                mode = mode,
                path = path,
                shortId = shortId,
                flow = flow
            )
            servers.add(parsedServer)

            AppLogger.registerSecrets(
                parsedServer.serverName,
                parsedServer.host,
                parsedServer.address,
                parsedServer.path
            )
            AppLogger.i(
                TAG,
                "Free node ${parsedServer.tag}: network=${parsedServer.network}, " +
                    "security=${parsedServer.security}, port=${parsedServer.port}, " +
                    "flow=${if (parsedServer.flow.isBlank()) "flowless" else "vision"}, " +
                    "mode=${parsedServer.mode}, path_present=${parsedServer.path.isNotBlank()}, " +
                    "path_query=${parsedServer.path.contains('?')}"
            )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed free outbound: ${e.message}")
            }
        }
    }

    private fun cacheLiveConfig(responseBody: String) {
        try {
            if (responseBody.length > MAX_CACHE_CHARS) return
            cacheFile().writeText(responseBody)
        } catch (_: Throwable) {

        }
    }

    private fun parseCachedLiveConfig(servers: MutableList<VlessServer>) {
        try {
            val file = cacheFile()
            if (!file.exists()) return
            if (System.currentTimeMillis() - file.lastModified() > CACHE_STALE_MS) {
                AppLogger.w(TAG, "Кэш панели старше суток, узлов не будет")
                return
            }
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val responseConfig = root.getAsJsonObject("config") ?: root
            val sharedUserId = extractUuid(root.get("v2rayKey")?.asString)
            parseOutbounds(responseConfig, servers, sharedUserId)
            if (servers.isNotEmpty()) {
                AppLogger.i(TAG, "Панель недоступна: используется кэш последней рабочей конфигурации")
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Кэш панели не читается: ${e.message}")
        }
    }

    private fun cacheFile(): File = File(context.filesDir, CACHE_FILE_NAME)

    private fun isCacheFresh(now: Long = System.currentTimeMillis()): Boolean {
        val file = cacheFile()
        return file.exists() && now - file.lastModified() < CACHE_AGE_MS
    }

    private fun cacheAgeMinutes(now: Long = System.currentTimeMillis()): Long {
        val file = cacheFile()
        if (!file.exists()) return -1L
        return (now - file.lastModified()) / 60_000L
    }

    private fun extractUuid(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        val uuidRegex = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        return uuidRegex.find(trimmed)?.value
    }

    companion object {
        private const val TAG = "VlessKeyRepository"
        private const val CACHE_FILE_NAME = "free_panel_cache.json"
        private const val MAX_CACHE_CHARS = 256 * 1024
        private const val CACHE_AGE_MS = 10 * 60 * 1000L
        private const val CACHE_STALE_MS = 24 * 60 * 60 * 1000L

        private val BASE_SEED = byteArrayOf(
            0x3E.toByte(), 0x71.toByte(), 0x95.toByte(), 0x2A.toByte(), 0x5D.toByte(), 0x8B.toByte(), 0x47.toByte(), 0x1C.toByte(),
            0xF0.toByte(), 0x6E.toByte(), 0xD3.toByte(), 0xA5.toByte(), 0x18.toByte(), 0x7F.toByte(), 0x24.toByte(), 0x9B.toByte(),
            0xC2.toByte(), 0x56.toByte(), 0x8D.toByte(), 0x31.toByte(), 0x4A.toByte(), 0x7E.toByte(), 0x0F.toByte(), 0x93.toByte(),
            0x68.toByte(), 0x2C.toByte(), 0xB4.toByte(), 0x51.toByte(), 0xE7.toByte(), 0x39.toByte(), 0x10.toByte(), 0x8F.toByte()
        )

        private val ENC_API_URL = byteArrayOf(
            0xF6.toByte(), 0x86.toByte(), 0xD1.toByte(), 0x2B.toByte(), 0xC6.toByte(), 0xE1.toByte(), 0xC1.toByte(), 0x8F.toByte(),
            0x2C.toByte(), 0x1A.toByte(), 0xE0.toByte(), 0x9F.toByte(), 0xBB.toByte(), 0x4B.toByte(), 0xB6.toByte(), 0x5E.toByte(),
            0x82.toByte(), 0xD6.toByte(), 0x58.toByte(), 0xBC.toByte(), 0xD5.toByte(), 0x23.toByte(), 0x59.toByte(), 0x42.toByte(),
            0x65.toByte(), 0x8A.toByte(), 0x00.toByte(), 0xB5.toByte(), 0xA8.toByte(), 0x4F.toByte(), 0xD7.toByte(), 0x6F.toByte(),
            0xF9.toByte(), 0x9B.toByte(), 0xD6.toByte(), 0x2F.toByte(), 0xD0.toByte(), 0xA9.toByte()
        )

}
}