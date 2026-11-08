package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
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
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors
import com.naua_security_mirage.app.util.AppLogger
import kotlinx.coroutines.flow.collectLatest
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

@Composable
fun LogsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val colors = LocalMirageColors.current
    var logLevel by remember { mutableStateOf(settingsRepository.logLevel) }
    var logs by remember { mutableStateOf(AppLogger.getAllLogs()) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val isEnglish = settingsRepository.appLanguage == SettingsRepository.LANG_EN

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
        // Logs Top Header matching viewLogs FrameLayout in activity_main.xml
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            // Back Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MirageColors.Card)
                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(12.dp))
                    .clickable { onBack() }
                    .align(Alignment.CenterStart),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (isEnglish) "Back" else "Назад",
                    tint = colors.ink,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Title
            Text(
                text = if (isEnglish) "Logs" else "Журнал",
                color = colors.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )

            // Clear Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MirageColors.Card)
                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(12.dp))
                    .clickable {
                        AppLogger.clear()
                        logs = emptyList()
                        actionMessage = if (isEnglish) "Logs cleared" else "Журнал очищен"
                    }
                    .align(Alignment.CenterEnd),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = if (isEnglish) "Clear" else "Очистить",
                    tint = colors.ink,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Log Level Selector Section Header
        Text(
            text = if (isEnglish) "LOG LEVEL" else "УРОВЕНЬ ЛОГИРОВАНИЯ",
            color = colors.inkSoft,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp)
        )

        // Chips horizontal scroll (Auto, Debug, Info, Warning, Error, None)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val levels = listOf(
                SettingsRepository.LOG_LEVEL_AUTO to "Auto",
                SettingsRepository.LOG_LEVEL_DEBUG to "Debug",
                SettingsRepository.LOG_LEVEL_INFO to "Info",
                SettingsRepository.LOG_LEVEL_WARNING to "Warning",
                SettingsRepository.LOG_LEVEL_ERROR to "Error",
                SettingsRepository.LOG_LEVEL_NONE to "None"
            )

            levels.forEach { (lvl, label) ->
                val isSelected = logLevel == lvl
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MirageColors.PillActiveBg else MirageColors.Card)
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) MirageColors.PillActiveBorder else MirageColors.CardStroke,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            logLevel = lvl
                            settingsRepository.logLevel = lvl
                            AppLogger.system("LogsScreen", "Log level changed to: $lvl")
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else colors.ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Log Viewer Card / Terminal
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MirageColors.Card)
                .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = if (isEnglish) "Real-time event log" else "Журнал событий в реальном времени",
                    color = colors.inkSoft,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (logs.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isEnglish) "Log is empty" else "Журнал чист",
                            color = colors.inkSoft,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { line ->
                            val lineColor = when {
                                line.contains("[ERROR]") -> Color(0xFFEF4444)
                                line.contains("[WARN]") -> Color(0xFFF59E0B)
                                line.contains("[SYSTEM]") -> MirageColors.AuAccent
                                line.contains("[DEBUG]") -> Color(0xFF60A5FA)
                                else -> colors.ink
                            }
                            Text(
                                text = line,
                                color = lineColor,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        if (actionMessage != null) {
            Text(
                text = actionMessage ?: "",
                color = MirageColors.AuAccent,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            )
        }

        // Bottom Action Buttons: Download and Share (Matching activity_main.xml)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Download Logs Button
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MirageColors.Card)
                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(14.dp))
                    .clickable {
                        try {
                            val formatted = AppLogger.getAllLogsFormatted()
                            val downloads = File(System.getProperty("user.home"), "Downloads")
                            val file = File(downloads, "mirage-logs.txt")
                            file.writeText(formatted)
                            actionMessage = if (isEnglish) "Saved to ${file.name}" else "Сохранено в Загрузки: ${file.name}"
                        } catch (e: Exception) {
                            actionMessage = "Ошибка: ${e.message}"
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isEnglish) "Download" else "Скачать",
                    color = colors.ink,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Share / Copy Logs Button
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MirageColors.Card)
                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(14.dp))
                    .clickable {
                        val formatted = AppLogger.getAllLogsFormatted()
                        val sel = StringSelection(formatted)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                        actionMessage = if (isEnglish) "Copied to clipboard" else "Скопировано в буфер обмена"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isEnglish) "Share" else "Поделиться",
                    color = colors.ink,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
