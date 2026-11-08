package com.naua_security_mirage.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors

@Composable
fun MirageHeader(
    title: String = "Mirage",
    subtitle: String = "NAUA Security",
    modifier: Modifier = Modifier,
    isSubScreen: Boolean = false,
    isRefreshing: Boolean = false,
    languageText: String = "RU",
    onLanguageClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    onUpdateClick: (() -> Unit)? = null,
    onRefreshClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    hasUpdate: Boolean = false
) {
    val colors = LocalMirageColors.current

    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    if (isSubScreen) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBackClick != null) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirageColors.AuSolid)
                        .border(1.dp, MirageColors.AuSep, RoundedCornerShape(12.dp))
                        .clickable { onBackClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = MirageColors.AuFg,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
            }

            Text(
                text = title,
                color = MirageColors.AuFg,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MirageColors.AuFg,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.01).sp
                )
                Text(
                    text = subtitle,
                    color = MirageColors.AuFg2,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Language Switcher (RU / EN)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirageColors.AuSolid)
                        .border(1.dp, MirageColors.AuSep, RoundedCornerShape(12.dp))
                        .clickable { onLanguageClick?.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = languageText,
                        color = MirageColors.AuFg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Update Button with 'New' Badge
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirageColors.AuSolid)
                        .border(1.dp, MirageColors.AuSep, RoundedCornerShape(12.dp))
                        .clickable { onUpdateClick?.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Обновления",
                        tint = MirageColors.AuFg,
                        modifier = Modifier.size(19.dp)
                    )

                    if (hasUpdate) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 2.dp, end = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF2E1F08))
                                .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(8.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFF59E0B))
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "New",
                                color = Color(0xFFF59E0B),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Refresh Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirageColors.AuSolid)
                        .border(1.dp, MirageColors.AuSep, RoundedCornerShape(12.dp))
                        .clickable { onRefreshClick?.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить сервера",
                        tint = MirageColors.AuFg,
                        modifier = Modifier
                            .size(19.dp)
                            .rotate(if (isRefreshing) rotation else 0f)
                    )
                }

                // Settings Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirageColors.AuSolid)
                        .border(1.dp, MirageColors.AuSep, RoundedCornerShape(12.dp))
                        .clickable { onSettingsClick?.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Настройки",
                        tint = MirageColors.AuFg,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
