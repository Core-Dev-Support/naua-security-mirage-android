package com.naua_security_mirage.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.ui.theme.MirageColors

@Composable
fun HideSectionsDialog(
    settingsRepository: SettingsRepository,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {}
) {
    val sections = listOf(
        "account" to "Аккаунт и подписка",
        "notifications" to "Уведомления и трей",
        "privacy" to "Безопасность и приватность",
        "backup" to "Резервное копирование",
        "split" to "Раздельное туннелирование",
        "diagnostics" to "Диагностика",
        "updates" to "Обновления"
    )

    var hiddenSet by remember { mutableStateOf(settingsRepository.hiddenSections) }

    fun toggle(id: String) {
        val next = hiddenSet.toMutableSet()
        if (next.contains(id)) next.remove(id) else next.add(id)
        hiddenSet = next
        settingsRepository.hiddenSections = next
        onChanged()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = MirageColors.Ink,
        modifier = Modifier.width(420.dp).height(540.dp),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Настройка разделов",
                                color = MirageColors.Ink,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Скрыть или отобразить блоки настроек",
                                color = MirageColors.InkSoft,
                                fontSize = 12.sp
                            )
                        }

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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val all = sections.map { it.first }.toSet()
                                hiddenSet = all
                                settingsRepository.hiddenSections = all
                                onChanged()
                            },
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = MirageColors.Bg,
                                contentColor = MirageColors.InkSoft
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MirageColors.CardStroke)
                        ) {
                            Text("Скрыть все", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = {
                                hiddenSet = emptySet()
                                settingsRepository.hiddenSections = emptySet()
                                onChanged()
                            },
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = MirageColors.Bg,
                                contentColor = MirageColors.Amber
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MirageColors.CardStroke)
                        ) {
                            Text("Показать все", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MirageColors.Bg)
                            .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(14.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            sections.forEach { (id, label) ->
                                val isVisible = !hiddenSet.contains(id)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { toggle(id) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isVisible,
                                        onCheckedChange = { toggle(id) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = MirageColors.Amber,
                                            uncheckedColor = MirageColors.InkSoft,
                                            checkmarkColor = Color.White
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = label,
                                        color = MirageColors.Ink,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
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
                            text = "Готово",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    )
}
