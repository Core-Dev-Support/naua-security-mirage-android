package com.naua_security_mirage.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.SessionJournal
import com.naua_security_mirage.app.ui.theme.MirageColors
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SessionHistoryDialog(onDismiss: () -> Unit) {
    val entries = remember { SessionJournal.list() }
    val dateFormat = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }

    fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h}ч ${m}м"
            m > 0 -> "${m}м ${s}с"
            else -> "${s}с"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = MirageColors.Ink,
        modifier = Modifier.width(420.dp).height(500.dp),
        text = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MirageColors.Card)
                    .border(1.2.dp, MirageColors.CardStroke, RoundedCornerShape(24.dp))
                    .padding(22.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "История сессий",
                            color = MirageColors.Ink,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = MirageColors.InkMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MirageColors.CardStroke)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MirageColors.Bg)
                            .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(14.dp))
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (entries.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "История сессий пока пуста",
                                    color = MirageColors.InkSoft,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                entries.forEach { entry ->
                                    val dateStr = dateFormat.format(Date(entry.startedAt))
                                    val durStr = formatDuration(entry.seconds)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$dateStr · ${entry.node} · $durStr",
                                            color = MirageColors.Ink,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MirageColors.PillActiveBg,
                            contentColor = MirageColors.Amber
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                    ) {
                        Text(
                            text = "Закрыть",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    )
}
