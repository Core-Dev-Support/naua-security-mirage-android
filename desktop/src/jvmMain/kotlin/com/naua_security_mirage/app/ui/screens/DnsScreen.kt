package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.theme.LocalMirageColors

@Composable
fun DnsScreen(
    onBack: () -> Unit
) {
    val colors = LocalMirageColors.current
    Column(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        MirageHeader(title = "DNS", isSubScreen = true, onBackClick = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "DNS настройки в разработке", color = colors.inkSoft)
        }
    }
}
