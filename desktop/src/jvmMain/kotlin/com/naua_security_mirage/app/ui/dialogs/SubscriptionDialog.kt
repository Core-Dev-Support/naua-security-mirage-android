package com.naua_security_mirage.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.supabase.PaymentManager
import com.naua_security_mirage.app.data.supabase.SupabaseConfig
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import kotlinx.coroutines.launch

@Composable
fun SubscriptionDialog(
    userId: String,
    onDismiss: () -> Unit,
    onSubscribed: () -> Unit
) {
    val colors = LocalMirageColors.current
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = colors.ink,
        modifier = Modifier.width(380.dp),
        text = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.card)
                    .border(1.dp, colors.cardStroke, RoundedCornerShape(20.dp))
                    .padding(22.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "🇫🇷",
                                fontSize = 22.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Сервер Франция (Платный)",
                                color = colors.ink,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
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

                    Text(
                        text = "Для подключения к выделенному серверу Франция требуется активная подписка Mirage Premium.",
                        color = colors.inkSoft,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Feature list
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.bg)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FeatureRow("⚡ Высокоскоростной выделенный канал 1 Гбит/с")
                        FeatureRow("🛡️ Протокол VLESS Reality (обход блокировок ТСПУ)")
                        FeatureRow("🔒 Персональный защищенный ключ доступа")
                        FeatureRow("🌐 Полный доступ к YouTube, зарубежным сервисам и ИИ")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Price Tag
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Стоимость подписки:",
                            color = colors.inkSoft,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${SupabaseConfig.subscriptionPriceRub} ₽ / 30 дней",
                            color = colors.accent,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (statusMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = statusMessage ?: "",
                            color = if (statusMessage?.contains("активна") == true) Color(0xFF10B981) else colors.inkSoft,
                            fontSize = 12.5.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Pay with YooMoney Button
                    Button(
                        onClick = {
                            PaymentManager.openPaymentBrowser(userId)
                            statusMessage = "Окно оплаты открыто в браузере. После оплаты нажмите «Проверить оплату»."
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = colors.accent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Payment,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Оплатить через ЮMoney",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Check Payment Status Button
                    OutlinedButton(
                        onClick = {
                            isChecking = true
                            scope.launch {
                                SupabaseManager.instance.refreshSubscription()
                                isChecking = false
                                if (SupabaseManager.instance.hasActiveSubscription()) {
                                    statusMessage = "Подписка активна! Создаем персональный ключ..."
                                    SupabaseManager.instance.ensureFranceVlessKey()
                                    onSubscribed()
                                    onDismiss()
                                } else {
                                    statusMessage = "Оплата пока не поступила. Если вы уже оплатили, подождите 1-2 минуты."
                                }
                            }
                        },
                        enabled = !isChecking,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = colors.card,
                            contentColor = colors.ink
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardStroke),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = colors.inkMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Проверить оплату",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun FeatureRow(text: String) {
    val colors = LocalMirageColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = colors.ink,
            fontSize = 12.sp
        )
    }
}
