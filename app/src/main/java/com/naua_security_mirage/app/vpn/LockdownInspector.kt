package com.naua_security_mirage.app.vpn

import android.content.Context
import android.os.Build
import android.provider.Settings

object LockdownInspector {

    fun isOurAppLocked(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val resolver = context.contentResolver
            val lockdown = Settings.Global.getInt(resolver, "always_on_vpn_lockdown", 0)
            if (lockdown != 1) return false
            val lockedApp = Settings.Global.getString(resolver, "always_on_vpn_app")
            lockedApp == context.packageName
        } catch (_: Throwable) {
            false
        }
    }
}
