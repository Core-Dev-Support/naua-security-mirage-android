package com.naua_security_mirage.app.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Properties
import java.util.concurrent.TimeUnit

class VlessKeyRepository(
    private val deviceIdRepository: DeviceIdRepository
) {

    private val httpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /**
     * Адрес API бесплатных узлов. В публичном репозитории его нет: он
     * приходит из секрета CI в ресурс `mirage.properties` при сборке.
     * Ключи при этом всегда регистрируются на устройство, общего UUID нет.
     */
    private val apiUrl: String by lazy {
        System.getenv("MIRAGE_API_URL")?.trim()?.takeIf { it.isNotEmpty() }
            ?: packagedApiUrl()
    }

    private fun packagedApiUrl(): String = try {
        javaClass.classLoader?.getResourceAsStream("mirage.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("apiUrl").orEmpty().trim()
        }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    suspend fun getVlessServers(): List<VlessServer> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<VlessServer>()
        val deviceId = deviceIdRepository.getOrCreateDeviceId()

        if (apiUrl.isBlank()) {
            AppLogger.w(TAG, "API бесплатных узлов не настроен: бесплатные узлы недоступны")
            return@withContext emptyList()
        }

        kotlinx.coroutines.withTimeoutOrNull(4000) {
            try {
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

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val respBody = response.body?.string()
                        if (!respBody.isNullOrEmpty()) {
                            val root = JsonParser.parseString(respBody).asJsonObject
                            // Внешний API заворачивает конфиг в `config`, но старые
                            // ответы отдавали outbounds на верхнем уровне.
                            val config = root.getAsJsonObject("config") ?: root
                            parseOutbounds(config, servers)
                        }
                    } else {
                        AppLogger.w(TAG, "API бесплатных узлов вернул код ${response.code}")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Не удалось получить бесплатные узлы: ${e.message}")
            }
            Unit
        }

        servers.take(3)
    }

    private fun parseOutbounds(root: JsonObject, servers: MutableList<VlessServer>) {
        val outbounds = root.getAsJsonArray("outbounds") ?: return
        for (elem in outbounds) {
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
            val users = vnext.getAsJsonArray("users") ?: continue
            if (users.size() == 0) continue

            val user = users[0].asJsonObject
            val uuid = user.get("id")?.asString ?: continue

            val streamSettings = obj.getAsJsonObject("streamSettings") ?: continue
            val network = streamSettings.get("network")?.asString ?: "tcp"
            val security = streamSettings.get("security")?.asString ?: "none"

            var publicKey = ""
            var fingerprint = ""
            var serverName = ""

            if (security == "reality") {
                val realitySettings = streamSettings.getAsJsonObject("realitySettings")
                if (realitySettings != null) {
                    publicKey = realitySettings.get("publicKey")?.asString ?: ""
                    fingerprint = realitySettings.get("fingerprint")?.asString ?: ""
                    serverName = realitySettings.get("serverName")?.asString ?: ""
                }
            }

            var path = ""
            var host = ""
            var mode = ""
            if (network == "xhttp") {
                val xhttpSettings = streamSettings.getAsJsonObject("xhttpSettings")
                if (xhttpSettings != null) {
                    path = xhttpSettings.get("path")?.asString ?: ""
                    host = xhttpSettings.get("host")?.asString ?: ""
                    mode = xhttpSettings.get("mode")?.asString ?: ""
                }
            }

            servers.add(
                VlessServer(
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
                    path = path
                )
            )
        }
    }

    companion object {
        private const val TAG = "VlessKeyRepository"
    }
}
