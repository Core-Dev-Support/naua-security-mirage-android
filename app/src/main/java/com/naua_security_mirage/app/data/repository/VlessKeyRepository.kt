package com.naua_security_mirage.app.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.naua_security_mirage.app.data.model.VlessServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private val apiUrl: String by lazy { mask(ENC_API_URL) }
    private val defaultUuid: String by lazy { mask(ENC_DEFAULT_UUID) }
    private val defaultPbk: String by lazy { mask(ENC_DEFAULT_PBK) }
    private val defaultSni: String by lazy { mask(ENC_DEFAULT_SNI) }
    private val fbHost1: String by lazy { mask(ENC_FB_HOST_1) }
    private val fbHost2: String by lazy { mask(ENC_FB_HOST_2) }
    private val fbHost3: String by lazy { mask(ENC_FB_HOST_3) }

    suspend fun getVlessServers(): List<VlessServer> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<VlessServer>()
        val deviceId = deviceIdRepository.getOrCreateDeviceId()

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

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val respBody = response.body?.string()
                    if (!respBody.isNullOrEmpty()) {
                        val root = JsonParser.parseString(respBody).asJsonObject
                        parseOutbounds(root, servers)
                    }
                } else {
                    Log.w(TAG, "API returned code ${response.code}, falling back to static servers")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch servers from API: ${e.message}, using fallback servers")
            }
            Unit
        }

        // Ensure we always have exactly 3 servers
        val fallbackList = getFallbackServers()
        for (fallback in fallbackList) {
            if (servers.size >= 3) break
            if (servers.none { it.address == fallback.address && it.port == fallback.port }) {
                servers.add(fallback)
            }
        }

        while (servers.size < 3 && fallbackList.isNotEmpty()) {
            val template = fallbackList[servers.size % fallbackList.size]
            val uniqueServer = template.copy(id = "fallback_clone_${servers.size + 1}")
            servers.add(uniqueServer)
        }

        servers.take(3)
    }

    private fun parseOutbounds(root: JsonObject, servers: MutableList<VlessServer>) {
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
            val users = vnext.getAsJsonArray("users") ?: continue
            if (users.size() == 0) continue

            val user = users[0].asJsonObject
            val uuid = user.get("id")?.asString ?: continue
            val flow = user.get("flow")?.asString.orEmpty()

            val streamSettings = obj.getAsJsonObject("streamSettings") ?: continue
            val network = streamSettings.get("network")?.asString ?: "tcp"
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
                    path = path,
                    shortId = shortId,
                    flow = flow
                )
            )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed free outbound: ${e.message}")
            }
        }
    }

    private fun getFallbackServers(): List<VlessServer> {
        return listOf(
            VlessServer(
                id = "fallback_1",
                tag = "Mirage Server #1",
                address = fbHost1,
                port = 3443,
                uuid = defaultUuid,
                network = "xhttp",
                security = "reality",
                publicKey = defaultPbk,
                fingerprint = "edge",
                serverName = defaultSni,
                host = defaultSni,
                mode = "packet-up",
                path = "/widgetComponent.js"
            ),
            VlessServer(
                id = "fallback_2",
                tag = "Mirage Server #2",
                address = fbHost2,
                port = 3443,
                uuid = defaultUuid,
                network = "xhttp",
                security = "reality",
                publicKey = defaultPbk,
                fingerprint = "edge",
                serverName = defaultSni,
                host = defaultSni,
                mode = "packet-up",
                path = "/widgetComponent.js"
            ),
            VlessServer(
                id = "fallback_3",
                tag = "Mirage Server #3",
                address = fbHost3,
                port = 3443,
                uuid = defaultUuid,
                network = "xhttp",
                security = "reality",
                publicKey = defaultPbk,
                fingerprint = "edge",
                serverName = defaultSni,
                host = defaultSni,
                mode = "packet-up",
                path = "/widgetComponent.js"
            )
        )
    }

    companion object {
        private const val TAG = "VlessKeyRepository"

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

        private val ENC_DEFAULT_UUID = byteArrayOf(
            0xAD.toByte(), 0x94.toByte(), 0x92.toByte(), 0x6B.toByte(), 0x81.toByte(), 0xBF.toByte(), 0xD7.toByte(), 0x90.toByte(),
            0x77.toByte(), 0x08.toByte(), 0xB2.toByte(), 0x86.toByte(), 0xBA.toByte(), 0x09.toByte(), 0xEE.toByte(), 0x0B.toByte(),
            0xDE.toByte(), 0xD9.toByte(), 0x10.toByte(), 0xA9.toByte(), 0xC2.toByte(), 0x6F.toByte(), 0x0E.toByte(), 0x1F.toByte(),
            0x7F.toByte(), 0xD9.toByte(), 0x41.toByte(), 0xA7.toByte(), 0xF8.toByte(), 0x53.toByte(), 0xC0.toByte(), 0x32.toByte(),
            0xA9.toByte(), 0xC1.toByte(), 0xC7.toByte(), 0x6C.toByte()
        )

        private val ENC_DEFAULT_PBK = byteArrayOf(
            0xB3.toByte(), 0xCB.toByte(), 0xFD.toByte(), 0x31.toByte(), 0xD3.toByte(), 0xB2.toByte(), 0x9A.toByte(), 0xEA.toByte(),
            0x0C.toByte(), 0x07.toByte(), 0xDF.toByte(), 0xD9.toByte(), 0x8E.toByte(), 0x53.toByte(), 0xB5.toByte(), 0x08.toByte(),
            0xB5.toByte(), 0xD3.toByte(), 0x79.toByte(), 0x84.toByte(), 0x9E.toByte(), 0x0D.toByte(), 0x5D.toByte(), 0x40.toByte(),
            0x3B.toByte(), 0xD8.toByte(), 0x1C.toByte(), 0xB7.toByte(), 0x99.toByte(), 0x07.toByte(), 0xC1.toByte(), 0x7E.toByte(),
            0xD1.toByte(), 0xAD.toByte(), 0xFF.toByte(), 0x0E.toByte(), 0xFC.toByte(), 0xB3.toByte(), 0xAA.toByte(), 0xEC.toByte(),
            0x18.toByte(), 0x28.toByte(), 0xE0.toByte()
        )

        private val ENC_DEFAULT_SNI = byteArrayOf(
            0xF2.toByte(), 0x9B.toByte(), 0xD3.toByte(), 0x3E.toByte(), 0x98.toByte(), 0xA8.toByte(), 0x9E.toByte(), 0xCF.toByte(),
            0x28.toByte(), 0x1E.toByte(), 0xA9.toByte(), 0xD2.toByte(), 0xBC.toByte(), 0x4A.toByte(), 0xF4.toByte(), 0x56.toByte(),
            0x86.toByte(), 0xD3.toByte(), 0x52.toByte(), 0xE6.toByte(), 0x8F.toByte(), 0x21.toByte()
        )

        private val ENC_FB_HOST_1 = byteArrayOf(
            0xE8.toByte(), 0x82.toByte(), 0xC2.toByte(), 0x76.toByte(), 0xCD.toByte(), 0xEA.toByte(), 0xC0.toByte(), 0xC6.toByte(),
            0x3B.toByte(), 0x04.toByte(), 0xF3.toByte(), 0xD8.toByte(), 0xBB.toByte(), 0x0A.toByte(), 0xAE.toByte(), 0x56.toByte(),
            0x9D.toByte()
        )

        private val ENC_FB_HOST_2 = byteArrayOf(
            0xE8.toByte(), 0x82.toByte(), 0xC2.toByte(), 0x76.toByte(), 0xCD.toByte(), 0xE9.toByte(), 0xC0.toByte(), 0xC6.toByte(),
            0x3B.toByte(), 0x04.toByte(), 0xF3.toByte(), 0xD8.toByte(), 0xBB.toByte(), 0x0A.toByte(), 0xAE.toByte(), 0x56.toByte(),
            0x9D.toByte()
        )

        private val ENC_FB_HOST_3 = byteArrayOf(
            0xE8.toByte(), 0x82.toByte(), 0xC2.toByte(), 0x76.toByte(), 0xCD.toByte(), 0xE8.toByte(), 0xC0.toByte(), 0xC6.toByte(),
            0x3B.toByte(), 0x04.toByte(), 0xF3.toByte(), 0xD8.toByte(), 0xBB.toByte(), 0x0A.toByte(), 0xAE.toByte(), 0x56.toByte(),
            0x9D.toByte()
        )
    }
}
