package com.naua_security_mirage.app.vpn

import android.content.Context
import android.provider.Settings

object PrivateDnsInspector {

    enum class Mode { OFF, OPPORTUNISTIC, STRICT, UNKNOWN }

    data class State(
        val mode: Mode,
        val specifier: String?,
        val rawModeText: String?,
        val rawModeInt: Int
    )

    fun read(context: Context): State {
        val resolver = context.contentResolver

        var rawModeText: String? = null
        try {
            rawModeText = Settings.Global.getString(resolver, KEY_MODE)?.trim()
        } catch (_: Throwable) {
        }

        var rawModeInt = -1
        try {
            rawModeInt = Settings.Global.getInt(resolver, KEY_MODE, -1)
        } catch (_: Throwable) {
        }

        var specifier: String? = null
        try {
            specifier = Settings.Global.getString(resolver, KEY_SPECIFIER)?.trim()
        } catch (_: Throwable) {
        }

        val mode = when {
            !rawModeText.isNullOrEmpty() -> when (rawModeText!!.lowercase()) {
                TEXT_OFF -> Mode.OFF
                TEXT_OPPORTUNISTIC -> Mode.OPPORTUNISTIC
                TEXT_STRICT -> Mode.STRICT
                else -> Mode.UNKNOWN
            }
            else -> when (rawModeInt) {
                MODE_OFF -> Mode.OFF
                MODE_OPPORTUNISTIC -> Mode.OPPORTUNISTIC
                MODE_STRICT -> Mode.STRICT
                else -> Mode.UNKNOWN
            }
        }

        return State(mode, specifier?.takeIf { it.isNotEmpty() }, rawModeText, rawModeInt)
    }

    fun describe(state: State): String {
        val base = when (state.mode) {
            Mode.OFF -> "выключен"
            Mode.OPPORTUNISTIC -> "автоматически"
            Mode.STRICT -> "строгий, провайдер=${state.specifier ?: "не указан"}"
            Mode.UNKNOWN -> "не удалось прочитать"
        }
        val raw = "text=${state.rawModeText ?: "-"} int=${state.rawModeInt}"
        val spec = state.specifier?.let { " specifier=$it" } ?: ""
        return "$base ($raw$spec)"
    }

    private const val KEY_MODE = "private_dns_mode"
    private const val KEY_SPECIFIER = "private_dns_specifier"

    private const val TEXT_OFF = "off"
    private const val TEXT_OPPORTUNISTIC = "opportunistic"
    private const val TEXT_STRICT = "strict"

    private const val MODE_OFF = 0
    private const val MODE_OPPORTUNISTIC = 1
    private const val MODE_STRICT = 2
}
