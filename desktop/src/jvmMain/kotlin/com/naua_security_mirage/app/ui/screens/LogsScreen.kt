package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.ui.components.MirageCard
import com.naua_security_mirage.app.ui.components.MirageChip
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.flow.collectLatest
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun LogsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val colors = LocalMirageColors.current
    var logLevel by remember { mutableStateOf(settingsRepository.logLevel) }
    var logs by remember { mutableStateOf(AppLogger.getAllLogs()) }
    val listState = rememberLazyListState()

    // Realtime log collector
    LaunchedEffect(Unit) {
        AppLogger.logFlow.collectLatest {
            logs = AppLogger.getAllLogs()
            if (logs.isNotEmpty()) {
                listState.animateScrollToItem(logs.size - 1)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        MirageHeader(
            title = "Журнал",
            isSubScreen = true,
            onBackClick = onBack
        )

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            // Log Level selector
            Text(
                text = "ДЕТАЛИЗАЦИЯ ЛОГИРОВАНИЯ",
                color = colors.inkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )

            val levels = listOf(
                SettingsRepository.LOG_LEVEL_AUTO to "Авто",
                SettingsRepository.LOG_LEVEL_DEBUG to "Debug",
                SettingsRepository.LOG_LEVEL_INFO to "Info",
                SettingsRepository.LOG_LEVEL_WARNING to "Warning",
                SettingsRepository.LOG_LEVEL_ERROR to "Error",
                SettingsRepository.LOG_LEVEL_NONE to "Откл"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                levels.forEach { (lvl, label) ->
                    MirageChip(
                        text = label,
                        selected = logLevel == lvl,
                        onClick = {
                            logLevel = lvl
                            settingsRepository.logLevel = lvl
                            AppLogger.system("LogsScreen", "Уровень логирования изменен на: $lvl")
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Записей: ${logs.size}",
                    color = colors.inkMuted,
                    fontSize = 12.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            val formatted = AppLogger.getAllLogsFormatted()
                            val sel = StringSelection(formatted)
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Копировать",
                            color = colors.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            AppLogger.clear()
                            logs = emptyList()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Очистить",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Log Output Box
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF07080D))
                .padding(12.dp)
        ) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Журнал чист",
                        color = colors.inkMuted,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { line ->
                        val lineColor = when {
                            line.contains("[ERROR]") -> Color(0xFFEF4444)
                            line.contains("[WARN]") -> Color(0xFFF59E0B)
                            line.contains("[SYSTEM]") -> colors.accent
                            line.contains("[DEBUG]") -> Color(0xFF60A5FA)
                            else -> colors.ink
                        }
                        Text(
                            text = line,
                            color = lineColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}
