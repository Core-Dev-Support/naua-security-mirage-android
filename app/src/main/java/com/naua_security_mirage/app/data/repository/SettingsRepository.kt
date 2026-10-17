package com.naua_security_mirage.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.naua_security_mirage.app.data.model.CustomWebsite
import com.naua_security_mirage.app.util.AppLogger

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mirage_settings_prefs", Context.MODE_PRIVATE)

    var isStatusNotificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_STATUS_NOTIFICATION, true)
        set(value) = prefs.edit().putBoolean(KEY_STATUS_NOTIFICATION, value).apply()

    var isQuickSettingsTileEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUICK_SETTINGS_TILE, true)
        set(value) = prefs.edit().putBoolean(KEY_QUICK_SETTINGS_TILE, value).apply()

    var isAnonymousTelemetryEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANONYMOUS_TELEMETRY, true)
        set(value) = prefs.edit().putBoolean(KEY_ANONYMOUS_TELEMETRY, value).apply()

    var themePreset: String
        get() = prefs.getString(KEY_THEME_PRESET, THEME_DARK) ?: THEME_DARK
        set(value) = prefs.edit().putString(KEY_THEME_PRESET, value).apply()

    var customBgColor: Int
        get() = prefs.getInt(KEY_CUSTOM_BG_COLOR, 0)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_BG_COLOR, value).apply()

    var customBgImagePath: String?
        get() = prefs.getString(KEY_CUSTOM_BG_IMAGE_PATH, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_BG_IMAGE_PATH, value).apply()

    var customConnectBtnColor: Int
        get() = prefs.getInt(KEY_CUSTOM_CONNECT_BTN_COLOR, 0)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_CONNECT_BTN_COLOR, value).apply()

    var connectBtnStyle: String
        get() = prefs.getString(KEY_CONNECT_BTN_STYLE, STYLE_STANDARD) ?: STYLE_STANDARD
        set(value) = prefs.edit().putString(KEY_CONNECT_BTN_STYLE, value).apply()

    var customActionBtnColor: Int
        get() = prefs.getInt(KEY_CUSTOM_ACTION_BTN_COLOR, 0)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_ACTION_BTN_COLOR, value).apply()

    var customTextColor: Int
        get() = prefs.getInt(KEY_CUSTOM_TEXT_COLOR, 0)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_TEXT_COLOR, value).apply()

    var customSwitchColor: Int
        get() = prefs.getInt(KEY_CUSTOM_SWITCH_COLOR, 0)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_SWITCH_COLOR, value).apply()

    var logLevel: String
        get() = prefs.getString(KEY_LOG_LEVEL, LOG_LEVEL_AUTO) ?: LOG_LEVEL_AUTO
        set(value) = prefs.edit().putString(KEY_LOG_LEVEL, value).apply()

    var bypassedAppPackages: Set<String>
        get() = prefs.getStringSet(KEY_BYPASSED_APP_PACKAGES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BYPASSED_APP_PACKAGES, value).apply()

    var speedIntervalSeconds: Int
        get() = prefs.getInt(KEY_SPEED_INTERVAL, DEFAULT_SPEED_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_SPEED_INTERVAL, value).apply()

    var autoPingIntervalSeconds: Int
        get() = prefs.getInt(KEY_AUTO_PING_INTERVAL, DEFAULT_AUTO_PING_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_AUTO_PING_INTERVAL, value).apply()

    var flowStrippedHosts: Set<String>
        get() = prefs.getStringSet(KEY_FLOW_STRIPPED_HOSTS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FLOW_STRIPPED_HOSTS, value).apply()

    var franceFingerprintOverride: String?
        get() = prefs.getString(KEY_FRANCE_FINGERPRINT, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_FRANCE_FINGERPRINT, value).apply()

    var isKillSwitchEnabled: Boolean
        get() = prefs.getBoolean(KEY_KILL_SWITCH, true)
        set(value) = prefs.edit().putBoolean(KEY_KILL_SWITCH, value).apply()

    var isAutoStartOnBootEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ON_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_ON_BOOT, value).apply()

    var isDirectRuEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIRECT_RU, true)
        set(value) = prefs.edit().putBoolean(KEY_DIRECT_RU, value).apply()

    var isAiModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_MODE, value).apply()

    var aiDnsServer: String
        get() {
            val stored = prefs.getString(KEY_AI_DNS_SERVER, null)?.trim()
            return if (stored.isNullOrEmpty()) DEFAULT_AI_DNS_SERVER else stored
        }
        set(value) = prefs.edit().putString(KEY_AI_DNS_SERVER, value.trim()).apply()

    var privateDnsDialogTime: Long
        get() = prefs.getLong(KEY_PRIVATE_DNS_DIALOG_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_PRIVATE_DNS_DIALOG_TIME, value).apply()

    var selectedDnsId: String
        get() = prefs.getString(KEY_SELECTED_DNS_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_DNS_ID, value).apply()

    var customDnsUrl: String
        get() = prefs.getString(KEY_CUSTOM_DNS_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_DNS_URL, value).apply()

    var autoDnsPickedId: String
        get() = prefs.getString(KEY_AUTO_DNS_PICKED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTO_DNS_PICKED, value).apply()

    var autoDnsPickedAt: Long
        get() = prefs.getLong(KEY_AUTO_DNS_PICKED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_AUTO_DNS_PICKED_AT, value).apply()

    var isScreenshotBlockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREENSHOT_BLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREENSHOT_BLOCK, value).apply()

    var isEmailMasked: Boolean
        get() = prefs.getBoolean(KEY_EMAIL_MASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_EMAIL_MASKED, value).apply()

    var hiddenSections: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_SECTIONS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN_SECTIONS, value).apply()

    var sectionOrder: String
        get() = prefs.getString(KEY_SECTION_ORDER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SECTION_ORDER, value).apply()

    var areAnimationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANIMATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_ANIMATIONS, value).apply()

    var isPaymentNotificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_PAYMENT_NOTIFICATION, true)
        set(value) = prefs.edit().putBoolean(KEY_PAYMENT_NOTIFICATION, value).apply()

    var isVpnConsentAccepted: Boolean
        get() = prefs.getBoolean(KEY_VPN_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_VPN_CONSENT, value).apply()

    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, LANG_RU) ?: LANG_RU
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    var geoLastUpdateTime: Long
        get() = prefs.getLong(KEY_GEO_LAST_UPDATE, 0L)
        set(value) = prefs.edit().putLong(KEY_GEO_LAST_UPDATE, value).apply()

    var updateSource: String
        get() = prefs.getString(KEY_UPDATE_SOURCE, UPDATE_SOURCE_GITHUB)
            ?.takeIf { it == UPDATE_SOURCE_GITHUB || it == UPDATE_SOURCE_UPTODOWN }
            ?: UPDATE_SOURCE_GITHUB
        set(value) = prefs.edit().putString(KEY_UPDATE_SOURCE, value).apply()

    var lastShownWhatsNewVersion: String?
        get() = prefs.getString(KEY_LAST_SHOWN_WHATS_NEW_VERSION, null)
        set(value) = prefs.edit().putString(KEY_LAST_SHOWN_WHATS_NEW_VERSION, value).apply()

    var selectedServerPlan: String
        get() = prefs.getString(KEY_SELECTED_SERVER_PLAN, PLAN_FREE) ?: PLAN_FREE
        set(value) = prefs.edit().putString(KEY_SELECTED_SERVER_PLAN, value).apply()

    var lastPaidPlan: String
        get() {
            val stored = prefs.getString(KEY_LAST_PAID_PLAN, PLAN_PREMIUM_FRANCE) ?: PLAN_PREMIUM_FRANCE
            return if (stored == PLAN_PREMIUM_BELARUS) PLAN_PREMIUM_BELARUS else PLAN_PREMIUM_FRANCE
        }
        set(value) {
            if (value == PLAN_PREMIUM_BELARUS || value == PLAN_PREMIUM_FRANCE) {
                prefs.edit().putString(KEY_LAST_PAID_PLAN, value).apply()
            }
        }

    var preferredFreeNodeKey: String?
        get() = prefs.getString(KEY_PREFERRED_FREE_NODE, null)
        set(value) = prefs.edit().putString(KEY_PREFERRED_FREE_NODE, value).apply()

    fun isPaidPlan(plan: String = selectedServerPlan): Boolean {
        return plan == PLAN_PREMIUM_FRANCE || plan == PLAN_PREMIUM_BELARUS
    }

    fun isAppProxied(packageName: String): Boolean {

        return !bypassedAppPackages.contains(packageName)
    }

    fun setAppProxied(packageName: String, isProxied: Boolean) {
        val current = bypassedAppPackages.toMutableSet()
        if (isProxied) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        bypassedAppPackages = current
    }

    fun setAllAppsProxied(isProxied: Boolean, packageNames: Collection<String>) {
        val current = bypassedAppPackages.toMutableSet()
        if (isProxied) {
            current.removeAll(packageNames.toSet())
        } else {
            current.addAll(packageNames)
        }
        bypassedAppPackages = current
    }

    fun getCustomWebsites(): List<CustomWebsite> {
        val raw = prefs.getString(KEY_CUSTOM_WEBSITES, null) ?: return emptyList()
        return try {
            parseCustomWebsites(raw)
        } catch (e: Throwable) {
            AppLogger.e(
                "Settings",
                "Список сайтов не прочитался (${e.javaClass.simpleName}, хранится ${raw.length} символов)"
            )
            emptyList()
        }
    }

    private fun parseCustomWebsites(raw: String): List<CustomWebsite> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (!text.startsWith("[")) {
            return text.split('\n').mapNotNull { line ->
                val parts = line.split('|')
                val domain = parts.getOrNull(0)?.trim().orEmpty()
                if (domain.length < 3 || !domain.contains('.')) null
                else CustomWebsite(domain = domain, isEnabled = parts.getOrNull(1)?.trim() != "0")
            }
        }
        val array = org.json.JSONArray(text)
        val out = ArrayList<CustomWebsite>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val domain = obj.optString("domain", "").trim()
            if (domain.length < 3 || !domain.contains('.')) continue
            out += CustomWebsite(domain = domain, isEnabled = obj.optBoolean("isEnabled", true))
        }
        return out
    }

    fun saveCustomWebsites(sites: List<CustomWebsite>) {
        val text = sites.joinToString("\n") { site ->
            "${site.domain}|${if (site.isEnabled) "1" else "0"}"
        }
        prefs.edit().putString(KEY_CUSTOM_WEBSITES, text).commit()
    }

    fun addCustomWebsite(domain: String): Boolean {
        val current = getCustomWebsites().toMutableList()
        if (current.any { it.domain.equals(domain, ignoreCase = true) }) {
            return false
        }
        current.add(0, CustomWebsite(domain = domain, isEnabled = true))
        saveCustomWebsites(current)
        return getCustomWebsites().any { it.domain.equals(domain, ignoreCase = true) }
    }

    fun removeCustomWebsite(domain: String) {
        val current = getCustomWebsites().filterNot { it.domain.equals(domain, ignoreCase = true) }
        saveCustomWebsites(current)
    }

    fun setCustomWebsiteEnabled(domain: String, isEnabled: Boolean) {
        val current = getCustomWebsites().map {
            if (it.domain.equals(domain, ignoreCase = true)) it.copy(isEnabled = isEnabled) else it
        }
        saveCustomWebsites(current)
    }

    fun clearCustomWebsites() {
        prefs.edit().remove(KEY_CUSTOM_WEBSITES).apply()
    }

    fun getActiveBypassedDomains(): Set<String> {
        return getCustomWebsites()
            .filter { it.isEnabled }
            .map { it.domain.lowercase().trim() }
            .toSet()
    }

    fun resetAppearanceToDefaults() {
        prefs.edit()
            .putString(KEY_THEME_PRESET, THEME_DARK)
            .remove(KEY_CUSTOM_BG_COLOR)
            .remove(KEY_CUSTOM_BG_IMAGE_PATH)
            .remove(KEY_CUSTOM_CONNECT_BTN_COLOR)
            .remove(KEY_CONNECT_BTN_STYLE)
            .remove(KEY_CUSTOM_ACTION_BTN_COLOR)
            .remove(KEY_CUSTOM_TEXT_COLOR)
            .remove(KEY_CUSTOM_SWITCH_COLOR)
            .commit()
    }

    companion object {
        const val KEY_CUSTOM_BG_IMAGE = "key_custom_bg_image_path"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_CUSTOM = "custom"

        const val STYLE_STANDARD = "standard"
        const val STYLE_3D_CYBER_EARTH = "3d_cyber_earth"
        const val STYLE_3D_QUANTUM_CORE = "3d_quantum_core"
        const val STYLE_3D_HOLO_SHIELD = "3d_holo_shield"
        const val STYLE_3D_REALISTIC_EARTH = "3d_realistic_earth"

        const val LOG_LEVEL_AUTO = "auto"
        const val LOG_LEVEL_DEBUG = "debug"
        const val LOG_LEVEL_INFO = "info"
        const val LOG_LEVEL_WARNING = "warning"
        const val LOG_LEVEL_ERROR = "error"
        const val LOG_LEVEL_NONE = "none"

        const val DEFAULT_AUTO_PING_INTERVAL = 3
        const val DEFAULT_SPEED_INTERVAL = 1

        const val UPDATE_SOURCE_GITHUB = "github"
        const val UPDATE_SOURCE_UPTODOWN = "uptodown"

        const val PLAN_FREE = "free"
        const val DEFAULT_AI_DNS_SERVER = "dns.comss.one"
        const val LANG_RU = "ru"
        const val LANG_EN = "en"
        const val PLAN_PREMIUM_FRANCE = "premium_france"
        const val PLAN_PREMIUM_BELARUS = "premium_belarus"

        private const val KEY_SELECTED_SERVER_PLAN = "key_selected_server_plan"
        private const val KEY_LAST_PAID_PLAN = "key_last_paid_plan"
        private const val KEY_PREFERRED_FREE_NODE = "key_preferred_free_node"
        private const val KEY_UPDATE_SOURCE = "key_update_source"
        private const val KEY_LAST_SHOWN_WHATS_NEW_VERSION = "key_last_shown_whats_new_version"

        private const val KEY_STATUS_NOTIFICATION = "key_status_notification"
        private const val KEY_QUICK_SETTINGS_TILE = "key_quick_settings_tile"
        private const val KEY_ANONYMOUS_TELEMETRY = "key_anonymous_telemetry"
        private const val KEY_THEME_PRESET = "key_theme_preset"
        private const val KEY_CUSTOM_BG_COLOR = "key_custom_bg_color"
        private const val KEY_CUSTOM_BG_IMAGE_PATH = "key_custom_bg_image_path"
        private const val KEY_CUSTOM_CONNECT_BTN_COLOR = "key_custom_connect_btn_color"
        private const val KEY_CONNECT_BTN_STYLE = "key_connect_btn_style"
        private const val KEY_CUSTOM_ACTION_BTN_COLOR = "key_custom_action_btn_color"
        private const val KEY_CUSTOM_TEXT_COLOR = "key_custom_text_color"
        private const val KEY_CUSTOM_SWITCH_COLOR = "key_custom_switch_color"
        private const val KEY_LOG_LEVEL = "key_log_level"
        private const val KEY_BYPASSED_APP_PACKAGES = "key_bypassed_app_packages"
        private const val KEY_SPEED_INTERVAL = "key_speed_interval"
        private const val KEY_AUTO_PING_INTERVAL = "key_auto_ping_interval"
        private const val KEY_FLOW_STRIPPED_HOSTS = "key_flow_stripped_hosts"
        private const val KEY_FRANCE_FINGERPRINT = "key_france_fingerprint"
        private const val KEY_KILL_SWITCH = "key_kill_switch"
        private const val KEY_AUTO_START_ON_BOOT = "key_auto_start_on_boot"
        private const val KEY_DIRECT_RU = "key_direct_ru"
        private const val KEY_AI_MODE = "key_ai_mode"
        private const val KEY_AI_DNS_SERVER = "key_ai_dns_server"
        private const val KEY_PRIVATE_DNS_DIALOG_TIME = "key_private_dns_dialog_time"
    private const val KEY_SELECTED_DNS_ID = "key_selected_dns_id"
    private const val KEY_CUSTOM_DNS_URL = "key_custom_dns_url"
    private const val KEY_AUTO_DNS_PICKED = "key_auto_dns_picked_id"
    private const val KEY_AUTO_DNS_PICKED_AT = "key_auto_dns_picked_at"
        private const val KEY_SCREENSHOT_BLOCK = "key_screenshot_block"
        private const val KEY_EMAIL_MASKED = "key_email_masked"
        private const val KEY_HIDDEN_SECTIONS = "key_hidden_sections"
        private const val KEY_SECTION_ORDER = "key_section_order"
        private const val KEY_ANIMATIONS = "key_animations_enabled"
        private const val KEY_PAYMENT_NOTIFICATION = "key_payment_notification"
        private const val KEY_VPN_CONSENT = "key_vpn_consent_accepted"
        private const val KEY_APP_LANGUAGE = "key_app_language"
        private const val KEY_GEO_LAST_UPDATE = "key_geo_last_update"
        private const val KEY_CUSTOM_WEBSITES = "key_custom_websites"

    }
}
