package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.model.DnsServer
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Composable
fun DnsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf(settingsRepository.selectedDnsId.ifBlank { DnsServer.AUTO_ID }) }
    var customUrl by remember { mutableStateOf(settingsRepository.customDnsUrl) }
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val servers = remember {
        buildList {
            add(DnsServer(id = DnsServer.AUTO_ID, url = ""))
            add(DnsServer(id = DnsServer.PLAIN_ID, url = ""))
            addAll(DnsServer.BUILT_IN)
        }
    }

    fun nameOf(id: String): String = when (id) {
        DnsServer.AUTO_ID -> "Автоматически"
        DnsServer.PLAIN_ID -> "Без изменений"
        "cloudflare" -> "Cloudflare"
        "google" -> "Google"
        "quad9" -> "Quad9"
        "adguard" -> "AdGuard"
        "mullvad" -> "Mullvad"
        "yandex" -> "Яндекс"
        "custom" -> "Свой адрес"
        else -> id
    }

    fun hintOf(id: String): String = when (id) {
        DnsServer.AUTO_ID -> "Выбирается автоматически"
        DnsServer.PLAIN_ID -> "Цепочка по умолчанию"
        "custom" -> customUrl.ifBlank { "https://example.com/dns-query" }
        else -> DnsServer.byId(id)?.url ?: ""
    }

    Column(modifier = Modifier.fillMaxSize().background(LocalMirageColors.current.bg)) {
        MirageHeader(title = "Шифрованный DNS", isSubScreen = true, onBackClick = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Text(
                text = "Резолвер, которым туннель спрашивает DNS. Остальные остаются запасными.",
                color = MirageColors.AuFg2, fontSize = 13.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (testing) return@Button
                        testing = true
                        results = emptyMap()
                        scope.launch(Dispatchers.IO) {
                            val client = OkHttpClient.Builder()
                                .connectTimeout(2, TimeUnit.SECONDS)
                                .readTimeout(2, TimeUnit.SECONDS)
                                .build()
                            val probeTargets = listOf(
                                "cloudflare" to "https://cloudflare-dns.com/dns-query?name=example.com&type=A",
                                "google" to "https://dns.google/resolve?name=example.com&type=A",
                                "quad9" to "https://dns.quad9.net/dns-query?name=example.com&type=A",
                                "adguard" to "https://dns.adguard-dns.com/resolve?name=example.com&type=A",
                                "mullvad" to "https://dns.mullvad.net/dns-query?name=example.com&type=A",
                                "yandex" to "https://common.dot.dns.yandex.net/dns-query?name=example.com&type=A"
                            )
                            val map = mutableMapOf<String, String>()
                            for ((id, url) in probeTargets) {
                                val t0 = System.currentTimeMillis()
                                val ok = try {
                                    val req = Request.Builder().url(url).header("Accept", "application/dns-json").build()
                                    client.newCall(req).execute().use { it.isSuccessful || it.code in 200..499 }
                                } catch (_: Exception) { false }
                                map[id] = if (ok) "${System.currentTimeMillis() - t0} мс" else "не отвечает"
                                withContext(Dispatchers.Main) { results = map.toMap() }
                            }
                            withContext(Dispatchers.Main) { testing = false }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = MirageColors.AuAccent, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !testing
                ) { Text(if (testing) "Проверяем..." else "Проверить все") }
            }
            Spacer(Modifier.height(12.dp))

            servers.forEach { srv ->
                val isSelected = selectedId == srv.id
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        selectedId = srv.id
                        settingsRepository.selectedDnsId = srv.id
                        if (srv.id == DnsServer.AUTO_ID) settingsRepository.autoDnsPickedId = ""
                    },
                    color = if (isSelected) MirageColors.AuAccent.copy(alpha = 0.12f) else MirageColors.AuSolid,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) MirageColors.AuAccent else MirageColors.AuSep)
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = nameOf(srv.id) + if (isSelected) "  ✓" else "",
                                color = if (isSelected) MirageColors.AuAccent else MirageColors.AuFg,
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(text = hintOf(srv.id), color = MirageColors.AuFg3, fontSize = 11.sp, maxLines = 1)
                        }
                        results[srv.id]?.let { res ->
                            Text(
                                text = res,
                                color = if (res.contains("мс")) MirageColors.AuAccent else MirageColors.AuFg3,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Свой DoH-адрес", color = MirageColors.AuFg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://example.com/dns-query", color = MirageColors.AuFg3) },
                singleLine = true,
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = MirageColors.AuFg, cursorColor = MirageColors.AuAccent,
                    focusedBorderColor = MirageColors.AuAccent, unfocusedBorderColor = MirageColors.AuSep,
                    backgroundColor = MirageColors.AuSolid
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val v = customUrl.trim()
                        if (v.startsWith("https://")) {
                            settingsRepository.customDnsUrl = v
                            settingsRepository.selectedDnsId = "custom"
                            selectedId = "custom"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = MirageColors.AuAccent, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Сохранить и выбрать") }
                if (customUrl.isNotBlank() || settingsRepository.customDnsUrl.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            customUrl = ""; settingsRepository.customDnsUrl = ""
                            if (selectedId == "custom") { selectedId = DnsServer.AUTO_ID; settingsRepository.selectedDnsId = DnsServer.AUTO_ID }
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MirageColors.AuSep),
                        colors = ButtonDefaults.outlinedButtonColors(backgroundColor = MirageColors.AuSolid, contentColor = MirageColors.AuFg2)
                    ) { Text("Удалить") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
