package com.naua_security_mirage.app.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.ui.components.MirageIcons
import com.naua_security_mirage.app.ui.theme.MirageColors
import java.awt.Desktop
import java.net.URI

@Composable
fun SupportWaysDialog(
    showDonations: Boolean = true,
    onDismiss: () -> Unit
) {
    val title = if (showDonations) "Поддержать проект" else "Сообщество и поддержка"
    val subtitle = if (showDonations) "Выберите удобный способ поддержки разработчика" else "Связь с командой разработчиков NAUA"
    val hint = if (showDonations) {
        "Все собранные средства идут на оплату выделенных серверов, доменов и разработку новых функций защиты."
    } else {
        "В нашем Telegram вы можете узнать о статусе серверов, задать вопрос или получить оперативную помощь."
    }

    fun openUrl(url: String) {
        onDismiss()
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))
            }
        } catch (_: Exception) {}
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = MirageColors.Ink,
        modifier = Modifier.width(420.dp),
        text = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(MirageColors.Card)
                    .border(1.2.dp, MirageColors.CardStroke, RoundedCornerShape(24.dp))
                    .padding(22.dp)
            ) {
                Column {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = MirageColors.Ink,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = subtitle,
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

                    // Hint Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MirageColors.Bg)
                            .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = hint,
                            color = MirageColors.InkSoft,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Items list
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showDonations) {
                            // 1. CloudTips
                            SupportWayCard(
                                icon = {
                                    Image(
                                        painter = painterResource("ic_cloudtips_logo.png"),
                                        contentDescription = "CloudTips",
                                        modifier = Modifier.size(38.dp)
                                    )
                                },
                                title = "CloudTips",
                                desc = "Банковские карты РФ, СБП, Tinkoff Pay, Yandex Pay",
                                onClick = { openUrl("https://pay.cloudtips.ru/p/82445608") }
                            )

                            // 2. Tribute Telegram
                            SupportWayCard(
                                icon = {
                                    Image(
                                        painter = painterResource("ic_tribute_logo.png"),
                                        contentDescription = "Tribute",
                                        modifier = Modifier.size(38.dp)
                                    )
                                },
                                title = "Tribute (Telegram)",
                                desc = "Донат и подписка через официального бота Tribute",
                                onClick = { openUrl("https://t.me/tribute/app?startapp=dS04") }
                            )

                            // 3. Tribute Web
                            SupportWayCard(
                                icon = {
                                    Image(
                                        painter = painterResource("ic_tribute_logo.png"),
                                        contentDescription = "Tribute Web",
                                        modifier = Modifier.size(38.dp)
                                    )
                                },
                                title = "Tribute Web",
                                desc = "Оплата картами любых стран на сайте сервиса",
                                onClick = { openUrl("https://web.tribute.tg/d/S04") }
                            )
                        } else {
                            // 1. Official Channel
                            SupportWayCard(
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF2AABEE).copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        MirageIcons.TelegramPlane(
                                            modifier = Modifier.size(20.dp),
                                            tint = Color(0xFF2AABEE)
                                        )
                                    }
                                },
                                title = "Официальный канал",
                                desc = "Новости, обновления приложения и статус серверов",
                                onClick = { openUrl("https://t.me/News_And_Updates_Apps") }
                            )

                            // 2. Support Chat
                            SupportWayCard(
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(MirageColors.Amber.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ChatBubble,
                                            contentDescription = null,
                                            tint = MirageColors.Amber,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                title = "Чат поддержки",
                                desc = "Прямая связь с разработчиками и решение проблем",
                                onClick = { openUrl("https://t.me/News_And_Updates_Apps?direct") }
                            )

                            // 3. Telegram Bot
                            SupportWayCard(
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(MirageColors.Amber.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SmartToy,
                                            contentDescription = null,
                                            tint = MirageColors.Amber,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                },
                                title = "Telegram-бот",
                                desc = "Управление подпиской, ключи и личный кабинет",
                                onClick = { openUrl("https://t.me/NAUA_Security_Mirage_Bot") }
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun SupportWayCard(
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MirageColors.Card)
            .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MirageColors.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                color = MirageColors.InkSoft,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MirageColors.PillActiveBg)
                .border(1.dp, MirageColors.PillActiveBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Перейти",
                color = MirageColors.Amber,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
