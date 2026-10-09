package com.naua_security_mirage.app.vpn

import android.content.Context
import android.provider.Settings

object PrivateDnsInspector {

    enum class Mode { OFF, OPPORTUNISTIC, STRICT, UNKNOWN }

    data class State(
        val mode: Mode,
        val specifier: String?,
        val rawMode: Int,
        val readError: String?
    )

    fun read(context: Context): State {
        var rawMode = -1
        var specifier: String? = null
        var error: String? = null
        try {
            val resolver = context.contentResolver
            rawMode = Settings.Global.getInt(resolver, KEY_MODE, -1)
        } catch (t: Throwable) {
            error = t.javaClass.simpleName + ": " + t.message
        }
        try {
            specifier = Settings.Global.getString(context.contentResolver, KEY_SPECIFIER)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
        }

        val mode = when (rawMode) {
            MODE_OFF -> Mode.OFF
            MODE_OPPORTUNISTIC -> Mode.OPPORTUNISTIC
            MODE_STRICT -> Mode.STRICT
            else -> Mode.UNKNOWN
        }
        return State(mode, specifier, rawMode, error)
    }

    fun describe(state: State): String {
        val base = when (state.mode) {
            Mode.OFF -> "выключен"
            Mode.OPPORTUNISTIC -> "автоматически"
            Mode.STRICT -> "строгий, провайдер=${state.specifier ?: "не указан"}"
            Mode.UNKNOWN -> "не удалось прочитать"
        }
        val raw = "raw=${state.rawMode}"
        val err = state.readError?.let { " ошибка=$it" } ?: ""
        val spec = state.specifier?.let { " specifier=$it" } ?: ""
        return "$base ($raw$spec$err)"
    }

    private const val KEY_MODE = "private_dns_mode"
    private const val KEY_SPECIFIER = "private_dns_specifier"
    private const val MODE_OFF = 0
    private const val MODE_OPPORTUNISTIC = 1
    private const val MODE_STRICT = 2
}
