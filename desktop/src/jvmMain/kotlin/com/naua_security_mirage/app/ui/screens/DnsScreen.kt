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
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {\n            Text(text = "Выбор DNS-сервера", color = colors.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)\n            Spacer(modifier = Modifier.height(12.dp))\n            listOf("Cloudflare", "Google", "Quad9", "Custom").forEach { name ->\n                androidx.compose.material.Surface(\n                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),\n                    color = colors.card,\n                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)\n                ) {\n                    Text(text = name, color = colors.ink, modifier = Modifier.padding(16.dp))\n                }\n            }\n        }
    }
}
