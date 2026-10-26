package com.naua_security_mirage.app.data.supabase

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

object ThreeXUiService {
    private const val TAG = "ThreeXUiService"
    private val gson = Gson()
    private val cookieStore = mutableMapOf<String, String>()

    // Системный TLS: панель обязана иметь валидный сертификат, иначе логин
    // с паролем администратора уйдёт в открытом или подменённом канале.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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

    private fun panelBaseUrl(): String? {
        val url = SupabaseConfig.THREE_X_UI_BASE_URL.trim().trimEnd('/')
        if (!url.startsWith("https://")) {
            AppLogger.error(TAG, "THREE_X_UI_BASE_URL must be an https:// URL")
            return null
        }
        return url
    }

    suspend fun login(): Boolean = withContext(Dispatchers.IO) {
        try {
            val baseUrl = panelBaseUrl() ?: return@withContext false

            val formBody = FormBody.Builder()
                .add("username", SupabaseConfig.THREE_X_UI_USERNAME)
                .add("password", SupabaseConfig.THREE_X_UI_PASSWORD)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/login")
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

    suspend fun getOrCreateClient(userEmail: String, clientUuid: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = panelBaseUrl() ?: return@withContext null
            if (!login()) {
                AppLogger.error(TAG, "Failed to login to 3X-UI before client operation")
                return@withContext null
            }

            val uuid = clientUuid ?: UUID.randomUUID().toString()
            val subId = uuid.replace("-", "").take(16)

            val expiryMs = System.currentTimeMillis() + (30L * 24L * 60L * 60L * 1000L)

            val clientObj = JsonObject().apply {
                addProperty("id", uuid)
                addProperty("email", userEmail)
                addProperty("flow", "xtls-rprx-vision")
                addProperty("limitIp", 2)
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
                .url("$baseUrl/panel/api/inbounds/addClient")
                .post(requestBody)
                .build()

            val response = client.newCall(addRequest).execute()
            response.body?.close()
            AppLogger.info(TAG, "3X-UI addClient HTTP ${response.code}")

            val vlessUrl = buildVlessUrl(uuid)
            vlessUrl
        } catch (e: Exception) {
            AppLogger.error(TAG, "Error in getOrCreateClient: ${e.message}")

            val uuid = clientUuid ?: UUID.randomUUID().toString()
            buildVlessUrl(uuid)
        }
    }

    fun buildVlessUrl(uuid: String): String {
        return "vless://${uuid}@${SupabaseConfig.FRANCE_HOST}:${SupabaseConfig.FRANCE_PORT}" +
                "?type=tcp" +
                "&security=reality" +
                "&pbk=${SupabaseConfig.FRANCE_PBK}" +
                "&fp=${SupabaseConfig.FRANCE_FINGERPRINT}" +
                "&sni=${SupabaseConfig.FRANCE_SNI}" +
                "&sid=${SupabaseConfig.FRANCE_SID}" +
                "&spx=${java.net.URLEncoder.encode(SupabaseConfig.FRANCE_SPX, "UTF-8")}" +
                "&flow=xtls-rprx-vision" +
                "#NAUA%20Mirage%20France%20(Premium)"
    }
}
