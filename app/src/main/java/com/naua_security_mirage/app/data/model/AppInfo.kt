package com.naua_security_mirage.app.data.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    var isProxied: Boolean = true
)
