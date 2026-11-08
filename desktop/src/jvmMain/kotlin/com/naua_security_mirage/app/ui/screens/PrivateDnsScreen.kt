package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PrivateDnsScreen(onBack: () -> Unit) {
    var dnsInfo by remember { mutableStateOf("Определяем состояние…") }
    var hasCustom by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
                    "Get-DnsClientServerAddress -AddressFamily IPv4 | Where-Object { \$_.ServerAddresses.Count -gt 0 -and \$_.InterfaceAlias -notmatch 'Loopback|Mirage|TAP|Wintun' } | Select-Object -First 1 -ExpandProperty ServerAddresses"
                )
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                val addrs = out.lines().map { it.trim() }.filter { it.isNotBlank() }
                if (addrs.isEmpty()) {
                    dnsInfo = "Системный DNS: автоматически (от сети)"
                    hasCustom = false
                } else {
                    val joined = addrs.joinToString(", ")
                    val isAuto = addrs.all { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
                    hasCustom = !isAuto
                    dnsInfo = if (isAuto) "DNS от сети: $joined" else "Задан DNS-сервер: $joined"
                }
            } catch (e: Exception) {
                dnsInfo = "Не удалось определить системный DNS"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(LocalMirageColors.current.bg)) {
        MirageHeader(title = "Приватный DNS", isSubScreen = true, onBackClick = onBack)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Surface(
                color = MirageColors.AuSolid, shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (hasCustom) Color(0xFFF59E0B).copy(alpha = 0.5f) else MirageColors.AuSep),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Состояние системы", color = MirageColors.AuFg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(dnsInfo, color = if (hasCustom) Color(0xFFF59E0B) else MirageColors.AuFg2, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(color = MirageColors.AuSolid, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MirageColors.AuSep)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("О функции", color = MirageColors.AuFg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "На Android «Приватный DNS» может направлять запросы мимо VPN. В Windows аналогичная ситуация — вручную заданный DNS адаптера может конфликтовать с туннелем. " +
                            "Если сайты не открываются, попробуйте установить DNS автоматически в настройках сетевого адаптера или используйте «DNS внутри туннеля» в Mirage.",
                        color = MirageColors.AuFg2, fontSize = 12.5.sp, lineHeight = 18.sp
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    try { ProcessBuilder("cmd", "/c", "start", "ms-settings:network-status").start() } catch (_: Exception) {}
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = MirageColors.AuAccent, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("Открыть сетевые настройки Windows", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
