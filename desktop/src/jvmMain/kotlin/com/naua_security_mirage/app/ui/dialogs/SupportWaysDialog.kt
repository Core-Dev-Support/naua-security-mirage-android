package com.naua_security_mirage.app.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import java.awt.Desktop
import java.net.URI

data class SupportWayItem(
    val emoji: String,
    val title: String,
    val desc: String,
    val url: String
)

@Composable
fun SupportWaysDialog(
    showDonations: Boolean = true,
    onDismiss: () -> Unit
) {
    val colors = LocalMirageColors.current

    val title = if (showDonations) "Поддержать проект" else "Сообщество и поддержка"
    val subtitle = if (showDonations) "Выберите удобный способ поддержки разработчика" else "Связь с командой разработчиков NAUA"
    val hint = if (showDonations) {
        "Все собранные средства идут на оплату выделенных серверов, доменов и разработку новых функций защиты."
    } else {
        "В нашем Telegram вы можете узнать о статусе серверов, задать вопрос или получить оперативную помощь."
    }

    val items = if (showDonations) {
        listOf(
            SupportWayItem(
                emoji = "💳",
                title = "CloudTips",
                desc = "Банковские карты РФ, СБП, Tinkoff Pay, Yandex Pay",
                url = "https://pay.cloudtips.ru/p/82445608"
            ),
            SupportWayItem(
                emoji = "⭐",
                title = "Tribute (Telegram)",
                desc = "Донат и подписка через официального бота Tribute",
                url = "https://t.me/tribute/app?startapp=dS04"
            ),
            SupportWayItem(
                emoji = "🌐",
                title = "Tribute Web",
                desc = "Оплата картами любых стран на сайте сервиса",
                url = "https://web.tribute.tg/d/S04"
            )
        )
    } else {
        listOf(
            SupportWayItem(
                emoji = "📢",
                title = "Официальный канал",
                desc = "Новости, обновления приложения и статус серверов",
                url = "https://t.me/News_And_Updates_Apps"
            ),
            SupportWayItem(
                emoji = "💬",
                title = "Чат поддержки",
                desc = "Прямая связь с разработчиками и решение проблем",
                url = "https://t.me/News_And_Updates_Apps?direct"
            ),
            SupportWayItem(
                emoji = "🤖",
                title = "Telegram-бот",
                desc = "Управление подпиской, ключи и личный кабинет",
                url = "https://t.me/NAUA_Security_Mirage_Bot"
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = colors.ink,
        modifier = Modifier.width(420.dp),
        text = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.card)
                    .border(1.2.dp, colors.cardStroke, RoundedCornerShape(24.dp))
                    .padding(22.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = colors.ink,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = subtitle,
                                color = colors.inkSoft,
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
                                tint = colors.inkMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.bg)
                            .border(1.dp, colors.cardStroke, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = hint,
                            color = colors.inkSoft,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(colors.card)
                                    .border(1.dp, colors.cardStroke, RoundedCornerShape(16.dp))
                                    .clickable {
                                        onDismiss()
                                        try {
                                            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                                Desktop.getDesktop().browse(URI(item.url))
                                            } else {
                                                Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", item.url))
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.emoji,
                                    fontSize = 24.sp
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        color = colors.ink,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.desc,
                                        color = colors.inkSoft,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFFE8A33D).copy(alpha = 0.15f))
                                        .border(1.dp, Color(0xFFE8A33D).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Перейти",
                                        color = Color(0xFFE8A33D),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}
