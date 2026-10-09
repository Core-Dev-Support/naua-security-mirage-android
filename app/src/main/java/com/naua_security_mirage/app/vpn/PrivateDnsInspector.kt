package com.naua_security_mirage.app.vpn

import android.content.Context
import android.provider.Settings

object PrivateDnsInspector {

    enum class Mode { OFF, OPPORTUNISTIC, STRICT, UNKNOWN }

    data class State(val mode: Mode, val specifier: String?)

    fun read(context: Context): State {
        return try {
            val resolver = context.contentResolver
            val rawMode = Settings.Global.getInt(resolver, KEY_MODE, -1)
            val specifier = try {
                Settings.Global.getString(resolver, KEY_SPECIFIER)
            } catch (_: Throwable) {
                null
            }
            val mode = when (rawMode) {
                MODE_OFF -> Mode.OFF
                MODE_OPPORTUNISTIC -> Mode.OPPORTUNISTIC
                MODE_STRICT -> Mode.STRICT
                else -> Mode.UNKNOWN
            }
            State(mode, specifier?.takeIf { it.isNotBlank() })
        } catch (_: Throwable) {
            State(Mode.UNKNOWN, null)
        }
    }

    fun describe(state: State): String = when (state.mode) {
        Mode.OFF -> "выключен"
        Mode.OPPORTUNISTIC -> "автоматически"
        Mode.STRICT -> "строгий, провайдер=${state.specifier ?: "не указан"}"
        Mode.UNKNOWN -> "не удалось прочитать"
    }

    private const val KEY_MODE = "private_dns_mode"
    private const val KEY_SPECIFIER = "private_dns_specifier"
    private const val MODE_OFF = 0
    private const val MODE_OPPORTUNISTIC = 1
    private const val MODE_STRICT = 2
}
