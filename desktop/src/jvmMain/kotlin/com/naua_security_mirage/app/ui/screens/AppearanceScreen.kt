package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.ui.components.MirageCard
import com.naua_security_mirage.app.ui.components.MirageChip
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors

@Composable
fun AppearanceScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    onAppearanceChanged: () -> Unit
) {
    val colors = LocalMirageColors.current
    val scrollState = rememberScrollState()

    var themePreset by remember { mutableStateOf(settingsRepository.themePreset) }
    var connectStyle by remember { mutableStateOf(settingsRepository.connectBtnStyle) }
    var selectedColor by remember { mutableStateOf(settingsRepository.customConnectBtnColor) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        MirageHeader(
            title = "Внешний вид",
            isSubScreen = true,
            onBackClick = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            // Section: Themes
            Text(
                text = "ТЕМА ОФОРМЛЕНИЯ",
                color = colors.inkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val themes = listOf(
                        SettingsRepository.THEME_DARK to "Тёмная",
                        SettingsRepository.THEME_LIGHT to "Светлая",
                        "amoled" to "AMOLED",
                        SettingsRepository.THEME_CUSTOM to "Пользовательская"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        themes.forEach { (id, label) ->
                            MirageChip(
                                text = label,
                                selected = themePreset == id,
                                onClick = {
                                    themePreset = id
                                    settingsRepository.themePreset = id
                                    onAppearanceChanged()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: 3D Visual Styles
            Text(
                text = "СТИЛЬ КНОПКИ ПОДКЛЮЧЕНИЯ",
                color = colors.inkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val styles = listOf(
                        SettingsRepository.STYLE_STANDARD to "Стандартная (Питание)",
                        SettingsRepository.STYLE_3D_CYBER_EARTH to "Кибер-Земля (3D)",
                        SettingsRepository.STYLE_3D_QUANTUM_CORE to "Квантовое ядро (3D)",
                        SettingsRepository.STYLE_3D_HOLO_SHIELD to "Голо-Щит (3D)",
                        SettingsRepository.STYLE_3D_REALISTIC_EARTH to "Реалистичная Земля (3D)"
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        styles.forEach { (id, label) ->
                            MirageCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        connectStyle = id
                                        settingsRepository.connectBtnStyle = id
                                        onAppearanceChanged()
                                    },
                                backgroundColor = if (connectStyle == id) colors.accent.copy(alpha = 0.12f) else colors.card,
                                borderColor = if (connectStyle == id) colors.accent else colors.cardStroke,
                                borderWidth = if (connectStyle == id) 2.dp else 1.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = label,
                                        color = if (connectStyle == id) colors.accent else colors.ink,
                                        fontSize = 14.sp,
                                        fontWeight = if (connectStyle == id) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (connectStyle == id) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = colors.accent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Accent Colors
            Text(
                text = "ЦВЕТОВОЙ АКЦЕНТ",
                color = colors.inkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    MirageColors.Swatches.forEach { colorInt ->
                        val color = Color(colorInt.toLong() and 0xFFFFFFFFL)
                        val isSelected = selectedColor == colorInt || (selectedColor == 0 && colorInt == MirageColors.Swatches.first())

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable {
                                    selectedColor = colorInt
                                    settingsRepository.customConnectBtnColor = colorInt
                                    settingsRepository.customActionBtnColor = colorInt
                                    onAppearanceChanged()
                                }
                                .then(
                                    if (isSelected) {
                                        Modifier.border(3.dp, Color.White, CircleShape)
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Reset Button
            MirageCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        settingsRepository.resetAppearanceToDefaults()
                        themePreset = SettingsRepository.THEME_DARK
                        connectStyle = SettingsRepository.STYLE_STANDARD
                        selectedColor = 0
                        onAppearanceChanged()
                    },
                borderColor = Color(0xFFEF4444).copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Сбросить оформление по умолчанию",
                        color = Color(0xFFEF4444),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
