package com.naua_security_mirage.app.data.supabase

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object ThreeXUiService {
    private const val TAG = "ThreeXUiService"
    private val gson = Gson()
    private val cookieStore = mutableMapOf<String, String>()

    // Trust-all SSL client for self-signed certificates on port 2053
    private val client: OkHttpClient = try {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    for (cookie in cookies) {
                        cookieStore[cookie.name] = cookie.value
                    }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore.map { (name, value) ->
                        Cookie.Builder()
                            .domain(url.host)
                            .name(name)
                            .value(value)
                            .build()
                    }
                }
            })
            .build()
    } catch (e: Exception) {
        AppLogger.error(TAG, "Error initializing SSL OkHttp client: ${e.message}")
        OkHttpClient()
    }

    /**
     * Authenticate with 3X-UI panel.
     */
    suspend fun login(): Boolean = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("username", SupabaseConfig.THREE_X_UI_USERNAME)
                .add("password", SupabaseConfig.THREE_X_UI_PASSWORD)
                .build()

            val request = Request.Builder()
                .url("${SupabaseConfig.THREE_X_UI_BASE_URL}/login")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            val success = body.contains("\"success\":true")
            AppLogger.info(TAG, "3X-UI login response success: $success")
            success
        } catch (e: Exception) {
            AppLogger.error(TAG, "3X-UI login failed: ${e.message}")
            false
        }
    }

    /**
     * Checks if the user already has an active, paid client in 3X-UI inbound 1.
     * Restores subscription even after logout, app reinstall, or across devices.
     *
     * @return SubscriptionDto if active client found, null if server responded but user not found.
     * @throws Exception if server is unreachable (login failed, network timeout, etc.)
     */
    suspend fun checkSubscription(userEmail: String, clientUuid: String? = null): SubscriptionDto? = withContext(Dispatchers.IO) {
        // Login — throws if login() returns false (auth error or network failure)
        if (!login()) {
            throw Exception("3X-UI login failed — server unreachable or auth error")
        }

        val request = Request.Builder()
            .url("${SupabaseConfig.THREE_X_UI_BASE_URL}/panel/api/inbounds/get/${SupabaseConfig.THREE_X_UI_INBOUND_ID}")
            .get()
            .build()

        // execute() throws IOException on network failure — let it propagate
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful || body.isEmpty()) return@withContext null

        val rootObj = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            AppLogger.error(TAG, "3X-UI response parse error: ${e.message}")
            return@withContext null
        }
        if (rootObj.get("success")?.asBoolean != true) return@withContext null

        val inboundObj = rootObj.getAsJsonObject("obj") ?: return@withContext null
        val settingsRaw = inboundObj.get("settings")?.asString.orEmpty()
        if (settingsRaw.isEmpty()) return@withContext null

        val settingsObj = try {
            gson.fromJson(settingsRaw, JsonObject::class.java)
        } catch (e: Exception) {
            AppLogger.error(TAG, "3X-UI settings parse error: ${e.message}")
            return@withContext null
        }
        val clientsArr = settingsObj.getAsJsonArray("clients") ?: return@withContext null

        // Dynamically extract live Reality streamSettings from 3X-UI inbound
        val streamSettingsRaw = inboundObj.get("streamSettings")?.asString.orEmpty()
        var livePbk = SupabaseConfig.FRANCE_PBK
        var liveSni = SupabaseConfig.FRANCE_SNI
        var liveSid = SupabaseConfig.FRANCE_SID
        var liveSpx = SupabaseConfig.FRANCE_SPX
        var liveFp = SupabaseConfig.FRANCE_FINGERPRINT
        if (streamSettingsRaw.isNotEmpty()) {
            try {
                val ssObj = gson.fromJson(streamSettingsRaw, JsonObject::class.java)
                val reality = ssObj.getAsJsonObject("realitySettings")
                if (reality != null) {
                    val sNames = reality.getAsJsonArray("serverNames")
                    if (sNames != null && sNames.size() > 0) {
                        liveSni = sNames[0].asString
                    }
                    val sIds = reality.getAsJsonArray("shortIds")
                    if (sIds != null && sIds.size() > 0) {
                        liveSid = sIds[0].asString
                    }
                    val rSettings = reality.getAsJsonObject("settings")
                    if (rSettings != null) {
                        livePbk = rSettings.get("publicKey")?.asString ?: livePbk
                        liveFp = rSettings.get("fingerprint")?.asString ?: liveFp
                        val rSpx = rSettings.get("spiderX")?.asString
                        if (!rSpx.isNullOrEmpty()) liveSpx = rSpx
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to parse live reality streamSettings: ${e.message}")
            }
        }

        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        for (elem in clientsArr) {
            try {
                val clientObj = elem.asJsonObject
                val email = clientObj.get("email")?.asString.orEmpty()
                val id = clientObj.get("id")?.asString.orEmpty()
                val flow = clientObj.get("flow")?.asString.orEmpty()
                val enabled = clientObj.get("enable")?.asBoolean ?: true
                val expiryTime = clientObj.get("expiryTime")?.asLong ?: 0L

                val isMatch = (email.isNotEmpty() && email.equals(userEmail, ignoreCase = true)) ||
                        (!clientUuid.isNullOrEmpty() && id.equals(clientUuid, ignoreCase = true))

                if (isMatch && enabled && (expiryTime == 0L || expiryTime > now)) {
                    val paidUntil = if (expiryTime > 0L) sdf.format(Date(expiryTime)) else "2099-01-01T00:00:00"
                    val vlessUrl = buildVlessUrl(
                        uuid = id,
                        flow = flow,
                        pbk = livePbk,
                        sni = liveSni,
                        sid = liveSid,
                        spx = liveSpx,
                        fp = liveFp
                    )
                    AppLogger.info(TAG, "Found active 3X-UI subscription for $userEmail, flow: '$flow', expires: $paidUntil")
                    return@withContext SubscriptionDto(
                        userId = id,
                        email = userEmail,
                        isActive = true,
                        plan = "premium",
                        paidUntil = paidUntil,
                        vlessKey = vlessUrl,
                        clientUuid = id,
                        flow = flow
                    )
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error parsing client entry: ${e.message}")
            }
        }
        AppLogger.info(TAG, "3X-UI responded OK but no active client found for $userEmail")
        null
    }

    /**
     * Get or create a dedicated client in 3X-UI inbound 2.
     * Returns personal VLESS link or null on error.
     */
    suspend fun getOrCreateClient(userEmail: String, clientUuid: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            // Check if active client already exists in 3X-UI to avoid duplicate error
            val existing = checkSubscription(userEmail, clientUuid)
            if (existing?.vlessKey != null) {
                AppLogger.info(TAG, "Existing client found for $userEmail, reusing key")
                return@withContext existing.vlessKey
            }

            if (!login()) {
                AppLogger.error(TAG, "Failed to login to 3X-UI before client operation")
                return@withContext null
            }

            val uuid = clientUuid ?: UUID.randomUUID().toString()
            val subId = uuid.replace("-", "").take(16)

            // 30 days expiry in milliseconds from now
            val expiryMs = System.currentTimeMillis() + (30L * 24L * 60L * 60L * 1000L)

            // Settings JSON payload for addClient
            val clientObj = JsonObject().apply {
                addProperty("id", uuid)
                addProperty("email", userEmail)
                addProperty("flow", "xtls-rprx-vision")
                addProperty("limitIp", 0)
                addProperty("totalGB", 0)
                addProperty("expiryTime", expiryMs)
                addProperty("enable", true)
                addProperty("tgId", "")
                addProperty("subId", subId)
            }

            val settingsJson = JsonObject().apply {
                val clientsArray = com.google.gson.JsonArray()
                clientsArray.add(clientObj)
                add("clients", clientsArray)
            }

            val requestJson = JsonObject().apply {
                addProperty("id", SupabaseConfig.THREE_X_UI_INBOUND_ID)
                addProperty("settings", gson.toJson(settingsJson))
            }

            val requestBody = gson.toJson(requestJson)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val addRequest = Request.Builder()
                .url("${SupabaseConfig.THREE_X_UI_BASE_URL}/panel/api/inbounds/addClient")
                .post(requestBody)
                .build()

            val response = client.newCall(addRequest).execute()
            val resBody = response.body?.string().orEmpty()
            AppLogger.info(TAG, "3X-UI addClient response: $resBody")

            // Build VLESS URL
            val vlessUrl = buildVlessUrl(uuid = uuid, flow = "xtls-rprx-vision")
            vlessUrl
        } catch (e: Exception) {
            AppLogger.error(TAG, "Error in getOrCreateClient: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Constructs a full VLESS reality URL matching Inbound 2.
     */
    fun buildVlessUrl(
        uuid: String,
        flow: String = "",
        pbk: String = SupabaseConfig.FRANCE_PBK,
        sni: String = SupabaseConfig.FRANCE_SNI,
        sid: String = SupabaseConfig.FRANCE_SID,
        spx: String = SupabaseConfig.FRANCE_SPX,
        fp: String = SupabaseConfig.FRANCE_FINGERPRINT
    ): String {
        val encodedSpx = java.net.URLEncoder.encode(spx, "UTF-8")
        val flowParam = if (flow.isNotBlank()) "&flow=$flow" else ""
        return "vless://${uuid}@${SupabaseConfig.FRANCE_HOST}:${SupabaseConfig.FRANCE_PORT}" +
                "?type=tcp" +
                "&security=reality" +
                "&pbk=$pbk" +
                "&fp=$fp" +
                "&sni=$sni" +
                "&sid=$sid" +
                "&spx=$encodedSpx" +
                flowParam +
                "#NAUA%20Mirage%20France%20(Premium)"
    }
}
