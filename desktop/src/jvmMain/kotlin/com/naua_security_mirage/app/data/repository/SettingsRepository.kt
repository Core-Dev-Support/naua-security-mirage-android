package com.naua_security_mirage.app.data.repository

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.naua_security_mirage.app.data.model.CustomWebsite
import java.io.File

class SettingsRepository {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dataDir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "NAUA Security Mirage").apply { mkdirs() }
    private val configFile: File = File(dataDir, "settings.json")

    private var state: SettingsState = loadSettings()

    init {
        try {
            File(dataDir, "backup").mkdirs()
            val logoDir = File(dataDir, "logo").apply { mkdirs() }
            val logoFile = File(logoDir, "icon.png")
            if (!logoFile.exists()) {
                javaClass.getResourceAsStream("/icon.png")?.use { input ->
                    logoFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (_: Exception) {}
    }

    data class SettingsState(
        var isStatusNotificationEnabled: Boolean = true,
        var isQuickSettingsTileEnabled: Boolean = true,
        var isAnonymousTelemetryEnabled: Boolean = true,
        var themePreset: String = THEME_DARK,
        var customBgColor: Int = 0,
        var customBgImagePath: String? = null,
        var customConnectBtnColor: Int = 0,
        var connectBtnStyle: String = STYLE_STANDARD,
        var customActionBtnColor: Int = 0,
        var customTextColor: Int = 0,
        var customSwitchColor: Int = 0,
        var logLevel: String = LOG_LEVEL_AUTO,
        var bypassedAppPackages: MutableSet<String> = mutableSetOf(),
        var speedIntervalSeconds: Int = DEFAULT_SPEED_INTERVAL,
        var autoPingIntervalSeconds: Int = DEFAULT_AUTO_PING_INTERVAL,
        var isKillSwitchEnabled: Boolean = true,
        var isAutoStartOnBootEnabled: Boolean = false,
        var isDirectRuEnabled: Boolean = true,
        var geoLastUpdateTime: Long = 0L,
        var updateSource: String = UPDATE_SOURCE_GITHUB,
        var lastShownWhatsNewVersion: String? = null,
        var customWebsites: MutableList<CustomWebsite> = mutableListOf(),
        var selectedDnsId: String = "",
        var customDnsUrl: String = "",
        var autoDnsPickedId: String = "",
        var autoDnsPickedAt: Long = 0L,
        var privateDnsDialogTime: Long = 0L,
        var selectedServerPlan: String = PLAN_FREE,
        var lastPaidPlan: String = PLAN_PREMIUM_FRANCE,
        var appLanguage: String = LANG_RU,
        var isEmailMasked: Boolean = false,
        var areAnimationsEnabled: Boolean = true
    )

    fun setAutoStartOnBoot(enabled: Boolean) {
        state.isAutoStartOnBootEnabled = enabled
        save()
        try {
            val regKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
            val appName = "NAUASecurityMirage"
            if (enabled) {
                val exe = ProcessHandle.current().info().command().orElse("")
                if (exe.isNotBlank()) {
                    Runtime.getRuntime().exec(arrayOf("reg", "add", regKey, "/v", appName, "/t", "REG_SZ", "/d", exe, "/f"))
                }
            } else {
                Runtime.getRuntime().exec(arrayOf("reg", "delete", regKey, "/v", appName, "/f"))
            }
        } catch (_: Exception) {}
    }

    private fun loadSettings(): SettingsState {
        return try {
            if (configFile.exists()) {
                val json = configFile.readText()
                gson.fromJson(json, SettingsState::class.java) ?: SettingsState()
            } else {
                SettingsState()
            }
        } catch (e: Exception) {
            SettingsState()
        }
    }

    @Synchronized
    private fun save() {
        try {
            val json = gson.toJson(state)
            configFile.writeText(json)
        } catch (_: Exception) {}
    }

    var isStatusNotificationEnabled: Boolean
        get() = state.isStatusNotificationEnabled
        set(value) { state.isStatusNotificationEnabled = value; save() }

    var isQuickSettingsTileEnabled: Boolean
        get() = state.isQuickSettingsTileEnabled
        set(value) { state.isQuickSettingsTileEnabled = value; save() }

    var isAnonymousTelemetryEnabled: Boolean
        get() = state.isAnonymousTelemetryEnabled
        set(value) { state.isAnonymousTelemetryEnabled = value; save() }

    var themePreset: String
        get() = state.themePreset
        set(value) { state.themePreset = value; save() }

    var customBgColor: Int
        get() = state.customBgColor
        set(value) { state.customBgColor = value; save() }

    var customBgImagePath: String?
        get() = state.customBgImagePath
        set(value) { state.customBgImagePath = value; save() }

    var customConnectBtnColor: Int
        get() = state.customConnectBtnColor
        set(value) { state.customConnectBtnColor = value; save() }

    var connectBtnStyle: String
        get() = state.connectBtnStyle
        set(value) { state.connectBtnStyle = value; save() }

    var customActionBtnColor: Int
        get() = state.customActionBtnColor
        set(value) { state.customActionBtnColor = value; save() }

    var customTextColor: Int
        get() = state.customTextColor
        set(value) { state.customTextColor = value; save() }

    var customSwitchColor: Int
        get() = state.customSwitchColor
        set(value) { state.customSwitchColor = value; save() }

    var logLevel: String
        get() = state.logLevel
        set(value) { state.logLevel = value; save() }

    var bypassedAppPackages: Set<String>
        get() = state.bypassedAppPackages
        set(value) { state.bypassedAppPackages = value.toMutableSet(); save() }

    var speedIntervalSeconds: Int
        get() = state.speedIntervalSeconds
        set(value) { state.speedIntervalSeconds = value; save() }

    var autoPingIntervalSeconds: Int
        get() = state.autoPingIntervalSeconds
        set(value) { state.autoPingIntervalSeconds = value; save() }

    var isKillSwitchEnabled: Boolean
        get() = state.isKillSwitchEnabled
        set(value) { state.isKillSwitchEnabled = value; save() }

    var isAutoStartOnBootEnabled: Boolean
        get() = state.isAutoStartOnBootEnabled
        set(value) { state.isAutoStartOnBootEnabled = value; save() }

    var isDirectRuEnabled: Boolean
        get() = state.isDirectRuEnabled
        set(value) { state.isDirectRuEnabled = value; save() }

    var geoLastUpdateTime: Long
        get() = state.geoLastUpdateTime
        set(value) { state.geoLastUpdateTime = value; save() }

    var updateSource: String
        get() = state.updateSource.takeIf { it == UPDATE_SOURCE_GITHUB || it == UPDATE_SOURCE_UPTODOWN }
            ?: UPDATE_SOURCE_GITHUB
        set(value) { state.updateSource = value; save() }

    var lastShownWhatsNewVersion: String?
        get() = state.lastShownWhatsNewVersion
        set(value) { state.lastShownWhatsNewVersion = value; save() }

    var selectedDnsId: String
        get() = state.selectedDnsId
        set(value) { state.selectedDnsId = value; save() }

    var customDnsUrl: String
        get() = state.customDnsUrl
        set(value) { state.customDnsUrl = value; save() }

    var autoDnsPickedId: String
        get() = state.autoDnsPickedId
        set(value) { state.autoDnsPickedId = value; save() }

    var autoDnsPickedAt: Long
        get() = state.autoDnsPickedAt
        set(value) { state.autoDnsPickedAt = value; save() }

    var privateDnsDialogTime: Long
        get() = state.privateDnsDialogTime
        set(value) { state.privateDnsDialogTime = value; save() }

    var selectedServerPlan: String
        get() = state.selectedServerPlan
        set(value) { state.selectedServerPlan = value; save() }

    var lastPaidPlan: String
        get() = state.lastPaidPlan
        set(value) { state.lastPaidPlan = value; save() }

    var appLanguage: String
        get() = state.appLanguage
        set(value) { state.appLanguage = value; save() }

    var isEmailMasked: Boolean
        get() = state.isEmailMasked
        set(value) { state.isEmailMasked = value; save() }

    var areAnimationsEnabled: Boolean
        get() = state.areAnimationsEnabled
        set(value) { state.areAnimationsEnabled = value; save() }

    fun isPaidPlan(plan: String = selectedServerPlan): Boolean = plan != PLAN_FREE

    fun isAppProxied(packageName: String): Boolean {
        return !state.bypassedAppPackages.contains(packageName)
    }

    fun setAppProxied(packageName: String, isProxied: Boolean) {
        if (isProxied) {
            state.bypassedAppPackages.remove(packageName)
        } else {
            state.bypassedAppPackages.add(packageName)
        }
        save()
    }

    fun setAllAppsProxied(isProxied: Boolean, packageNames: Collection<String>) {
        if (isProxied) {
            state.bypassedAppPackages.removeAll(packageNames.toSet())
        } else {
            state.bypassedAppPackages.addAll(packageNames)
        }
        save()
    }

    fun getCustomWebsites(): List<CustomWebsite> = state.customWebsites.toList()

    fun saveCustomWebsites(sites: List<CustomWebsite>) {
        state.customWebsites = sites.toMutableList()
        save()
    }

    fun addCustomWebsite(domain: String): Boolean {
        if (state.customWebsites.any { it.domain.equals(domain, ignoreCase = true) }) {
            return false
        }
        state.customWebsites.add(0, CustomWebsite(domain = domain, isEnabled = true))
        save()
        return true
    }

    fun removeCustomWebsite(domain: String) {
        state.customWebsites.removeAll { it.domain.equals(domain, ignoreCase = true) }
        save()
    }

    fun setCustomWebsiteEnabled(domain: String, isEnabled: Boolean) {
        state.customWebsites = state.customWebsites.map {
            if (it.domain.equals(domain, ignoreCase = true)) it.copy(isEnabled = isEnabled) else it
        }.toMutableList()
        save()
    }

    fun clearCustomWebsites() {
        state.customWebsites.clear()
        save()
    }

    fun getActiveBypassedDomains(): Set<String> {
        return state.customWebsites
            .filter { it.isEnabled }
            .map { it.domain.lowercase().trim() }
            .toSet()
    }

    fun resetAppearanceToDefaults() {
        state.themePreset = THEME_DARK
        state.customBgColor = 0
        state.customBgImagePath = null
        state.customConnectBtnColor = 0
        state.connectBtnStyle = STYLE_STANDARD
        state.customActionBtnColor = 0
        state.customTextColor = 0
        state.customSwitchColor = 0
        state.areAnimationsEnabled = true
        save()
    }

    companion object {
        const val PLAN_FREE = "free"
        const val PLAN_PREMIUM_FRANCE = "premium_france"
        const val PLAN_PREMIUM_BELARUS = "premium_belarus"

        const val LANG_RU = "ru"
        const val LANG_EN = "en"

        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_AMOLED = "amoled"
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
    }
}
