package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
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
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.components.MirageSwitch
import com.naua_security_mirage.app.ui.theme.MirageColors
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun AppearanceScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    onAppearanceChanged: () -> Unit
) {
    val scrollState = rememberScrollState()

    var themePreset by remember { mutableStateOf(settingsRepository.themePreset) }
    var connectStyle by remember { mutableStateOf(settingsRepository.connectBtnStyle) }
    var customBgColor by remember { mutableStateOf(settingsRepository.customBgColor) }
    var customBgImage by remember { mutableStateOf(settingsRepository.customBgImagePath) }
    var customConnectColor by remember { mutableStateOf(settingsRepository.customConnectBtnColor) }
    var customActionColor by remember { mutableStateOf(settingsRepository.customActionBtnColor) }
    var customTextColor by remember { mutableStateOf(settingsRepository.customTextColor) }
    var customSwitchColor by remember { mutableStateOf(settingsRepository.customSwitchColor) }
    var animationsEnabled by remember { mutableStateOf(settingsRepository.areAnimationsEnabled) }

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
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {

            // 1. ГОТОВЫЕ ТЕМЫ (Matching App_Slide_4.jpg)
            Text(
                text = "ГОТОВЫЕ ТЕМЫ",
                color = MirageColors.InkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp, start = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isDark = themePreset == SettingsRepository.THEME_DARK || themePreset == SettingsRepository.THEME_AMOLED
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(if (isDark) MirageColors.PillActiveBg else MirageColors.Card)
                        .border(
                            width = if (isDark) 1.5.dp else 1.dp,
                            color = if (isDark) MirageColors.PillActiveBorder else MirageColors.CardStroke,
                            shape = RoundedCornerShape(30.dp)
                        )
                        .clickable {
                            themePreset = SettingsRepository.THEME_DARK
                            settingsRepository.themePreset = SettingsRepository.THEME_DARK
                            onAppearanceChanged()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Тёмная",
                        color = if (isDark) MirageColors.Amber else MirageColors.InkSoft,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                val isLight = themePreset == SettingsRepository.THEME_LIGHT
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(if (isLight) MirageColors.PillActiveBg else MirageColors.Card)
                        .border(
                            width = if (isLight) 1.5.dp else 1.dp,
                            color = if (isLight) MirageColors.PillActiveBorder else MirageColors.CardStroke,
                            shape = RoundedCornerShape(30.dp)
                        )
                        .clickable {
                            themePreset = SettingsRepository.THEME_LIGHT
                            settingsRepository.themePreset = SettingsRepository.THEME_LIGHT
                            onAppearanceChanged()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Светлая",
                        color = if (isLight) MirageColors.Amber else MirageColors.InkSoft,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. ЗАДНИЙ ФОН (Matching App_Slide_4.jpg)
            Text(
                text = "ЗАДНИЙ ФОН",
                color = MirageColors.InkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.bg)
                                .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(12.dp))
                                .clickable {
                                    try {
                                        val dialog = FileDialog(null as Frame?, "Выберите фоновое изображение", FileDialog.LOAD)
                                        dialog.setFilenameFilter { _, name ->
                                            name.endsWith(".jpg", true) || name.endsWith(".png", true) || name.endsWith(".jpeg", true)
                                        }
                                        dialog.isVisible = true
                                        val dir = dialog.directory
                                        val file = dialog.file
                                        if (dir != null && file != null) {
                                            val fullPath = File(dir, file).absolutePath
                                            customBgImage = fullPath
                                            settingsRepository.customBgImagePath = fullPath
                                            onAppearanceChanged()
                                        }
                                    } catch (_: Exception) {}
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MirageColors.Ink,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (customBgImage != null) "Изображение выбрано" else "Выбрать фото из галереи",
                                    color = MirageColors.Ink,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (customBgImage != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.bg)
                                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(12.dp))
                                    .clickable {
                                        customBgImage = null
                                        settingsRepository.customBgImagePath = null
                                        onAppearanceChanged()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Удалить фон",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val bgSwatches = listOf(
                        0x00000000,          // Default
                        0xFF06070B.toInt(), // Deep Black
                        0xFF0F172A.toInt(), // Dark Navy
                        0xFF18181B.toInt(), // Dark Zinc
                        0xFF1E1B4B.toInt(), // Dark Indigo
                        0xFF064E3B.toInt(), // Dark Forest
                        0xFF450A0A.toInt()  // Dark Maroon
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        bgSwatches.forEach { colorInt ->
                            val color = if (colorInt == 0) MirageColors.Bg else Color(colorInt.toLong() and 0xFFFFFFFFL)
                            val isSelected = customBgColor == colorInt

                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) Color.White else MirageColors.CardStroke,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        customBgColor = colorInt
                                        settingsRepository.customBgColor = colorInt
                                        onAppearanceChanged()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. СТИЛЬ КНОПКИ ПОДКЛЮЧЕНИЯ (Matching App_Slide_4.jpg)
            Text(
                text = "СТИЛЬ КНОПКИ ПОДКЛЮЧЕНИЯ",
                color = MirageColors.InkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val styles = listOf(
                        SettingsRepository.STYLE_STANDARD to "Стандарт",
                        SettingsRepository.STYLE_3D_CYBER_EARTH to "3D Cyber Earth",
                        SettingsRepository.STYLE_3D_QUANTUM_CORE to "3D Quantum Core",
                        SettingsRepository.STYLE_3D_HOLO_SHIELD to "3D Holo-Shield",
                        SettingsRepository.STYLE_3D_REALISTIC_EARTH to "3D Realistic Earth"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        styles.forEach { (id, label) ->
                            val isSelected = connectStyle == id
                            Box(
                                modifier = Modifier
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(30.dp))
                                    .background(if (isSelected) MirageColors.PillActiveBg else MirageColors.Bg)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) MirageColors.PillActiveBorder else MirageColors.CardStroke,
                                        shape = RoundedCornerShape(30.dp)
                                    )
                                    .clickable {
                                        connectStyle = id
                                        settingsRepository.connectBtnStyle = id
                                        onAppearanceChanged()
                                    }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) MirageColors.Amber else MirageColors.InkFaint)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = label,
                                        color = if (isSelected) MirageColors.Amber else MirageColors.Ink,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val desc = when (connectStyle) {
                        SettingsRepository.STYLE_STANDARD -> "Классический шар — перекрашивается под темы и цвета."
                        SettingsRepository.STYLE_3D_CYBER_EARTH -> "3D-сфера планеты с неоновой сеткой координат и орбитальными кольцами."
                        SettingsRepository.STYLE_3D_QUANTUM_CORE -> "Квантовое ядро с вращающимися орбитами частиц и пульсирующей энергией."
                        SettingsRepository.STYLE_3D_HOLO_SHIELD -> "Голографический щит с защитными силовыми полями."
                        SettingsRepository.STYLE_3D_REALISTIC_EARTH -> "Реалистичная Земля с фотореалистичным рельефом и атмосферой."
                        else -> "Классический шар — перекрашивается под темы и цвета."
                    }

                    Text(
                        text = desc,
                        color = MirageColors.InkSoft,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. ЦВЕТ КНОПКИ ПОДКЛЮЧЕНИЯ (Matching App_Slide_4.jpg)
            Text(
                text = "ЦВЕТ КНОПКИ ПОДКЛЮЧЕНИЯ",
                color = MirageColors.InkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MirageColors.Swatches.forEach { colorInt ->
                        val color = Color(colorInt.toLong() and 0xFFFFFFFFL)
                        val isSelected = customConnectColor == colorInt || (customConnectColor == 0 && colorInt == MirageColors.Swatches.first())

                        ColorSwatchCircle(color = color, isSelected = isSelected) {
                            customConnectColor = colorInt
                            settingsRepository.customConnectBtnColor = colorInt
                            onAppearanceChanged()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 5. ВЕРХНИЕ КНОПКИ (Matching App_Slide_4.jpg)
            Text(
                text = "ВЕРХНИЕ КНОПКИ",
                color = MirageColors.InkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MirageColors.ActionBtnSwatches.forEach { colorInt ->
                        val color = Color(colorInt.toLong() and 0xFFFFFFFFL)
                        val isSelected = customActionColor == colorInt || (customActionColor == 0 && colorInt == MirageColors.ActionBtnSwatches.first())

                        ColorSwatchCircle(color = color, isSelected = isSelected) {
                            customActionColor = colorInt
                            settingsRepository.customActionBtnColor = colorInt
                            onAppearanceChanged()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 6. ЦВЕТ ТЕКСТА (Matching App_Slide_4.jpg)
            Text(
                text = "ЦВЕТ ТЕКСТА",
                color = MirageColors.InkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MirageColors.TextSwatches.forEach { colorInt ->
                        val color = Color(colorInt.toLong() and 0xFFFFFFFFL)
                        val isSelected = customTextColor == colorInt || (customTextColor == 0 && colorInt == MirageColors.TextSwatches.first())

                        ColorSwatchCircle(color = color, isSelected = isSelected) {
                            customTextColor = colorInt
                            settingsRepository.customTextColor = colorInt
                            onAppearanceChanged()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 7. КНОПКИ ПЕРЕКЛЮЧЕНИЯ (Matching App_Slide_5.jpg)
            Text(
                text = "КНОПКИ ПЕРЕКЛЮЧЕНИЯ",
                color = MirageColors.InkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MirageColors.SwitchSwatches.forEach { colorInt ->
                        val color = Color(colorInt.toLong() and 0xFFFFFFFFL)
                        val isSelected = customSwitchColor == colorInt || (customSwitchColor == 0 && colorInt == MirageColors.SwitchSwatches.first())

                        ColorSwatchCircle(color = color, isSelected = isSelected) {
                            customSwitchColor = colorInt
                            settingsRepository.customSwitchColor = colorInt
                            onAppearanceChanged()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 8. АНИМАЦИИ
            Text(
                text = "АНИМАЦИИ",
                color = MirageColors.InkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            animationsEnabled = !animationsEnabled
                            settingsRepository.areAnimationsEnabled = animationsEnabled
                            onAppearanceChanged()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Плавные анимации интерфейса",
                            color = MirageColors.Ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Анимации пульсации, свечения и переходов экранов",
                            color = MirageColors.InkSoft,
                            fontSize = 12.sp
                        )
                    }
                    MirageSwitch(
                        checked = animationsEnabled,
                        onCheckedChange = {
                            animationsEnabled = it
                            settingsRepository.areAnimationsEnabled = it
                            onAppearanceChanged()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 9. Сбросить по умолчанию (Matching App_Slide_5.jpg)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(MirageColors.Card)
                    .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f), RoundedCornerShape(30.dp))
                    .clickable {
                        settingsRepository.resetAppearanceToDefaults()
                        themePreset = SettingsRepository.THEME_DARK
                        connectStyle = SettingsRepository.STYLE_STANDARD
                        customBgColor = 0
                        customBgImage = null
                        customConnectColor = 0
                        customActionColor = 0
                        customTextColor = 0
                        customSwitchColor = 0
                        animationsEnabled = true
                        onAppearanceChanged()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Сбросить по умолчанию",
                    color = Color(0xFFEF4444),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ColorSwatchCircle(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) Color.White else MirageColors.CardStroke,
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
