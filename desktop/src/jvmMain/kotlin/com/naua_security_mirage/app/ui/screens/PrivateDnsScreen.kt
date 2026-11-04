package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.theme.LocalMirageColors

@Composable
fun PrivateDnsScreen(onBack: () -> Unit) {
    val colors = LocalMirageColors.current
    Column(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        MirageHeader(title = "Приватный DNS", isSubScreen = true, onBackClick = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "В разработке", color = colors.inkSoft)
        }
    }
}
