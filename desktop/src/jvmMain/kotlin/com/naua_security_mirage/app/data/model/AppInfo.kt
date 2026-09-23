package com.naua_security_mirage.app.data.model

data class AppInfo(
    val appName: String,
    val packageName: String,
    val exePath: String = packageName,
    val isSystemApp: Boolean = false,
    var isProxied: Boolean = true
)
