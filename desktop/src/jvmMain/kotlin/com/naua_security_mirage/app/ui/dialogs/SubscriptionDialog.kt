package com.naua_security_mirage.app.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.config.AppConfigRepository
import com.naua_security_mirage.app.data.config.SubscriptionPlan
import com.naua_security_mirage.app.data.repository.DeviceIdRepository
import com.naua_security_mirage.app.data.supabase.PaymentManager
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.theme.MirageColors
import kotlinx.coroutines.launch

@Composable
fun SubscriptionDialog(
    userId: String,
    onDismiss: () -> Unit,
    onSubscribed: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val appConfig by AppConfigRepository.config.collectAsState()

    var showPaymentOptions by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSuccessStatus by remember { mutableStateOf(false) }
    var trialLoading by remember { mutableStateOf(false) }

    if (showPaymentOptions) {
        AlertDialog(
            onDismissRequest = { showPaymentOptions = false },
            buttons = {},
            backgroundColor = Color.Transparent,
            contentColor = MirageColors.Ink,
            modifier = Modifier.width(380.dp),
            text = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(MirageColors.Card)
                        .border(1.2.dp, MirageColors.CardStroke, RoundedCornerShape(24.dp))
                        .padding(22.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Период подписки",
                                    color = MirageColors.Ink,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Чем больше период, тем выгоднее стоимость",
                                    color = MirageColors.InkSoft,
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(
                                onClick = { showPaymentOptions = false },
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

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            appConfig.plans.forEach { plan ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MirageColors.Bg)
                                        .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(14.dp))
                                        .clickable {
                                            PaymentManager.openPaymentBrowser(userId, plan.priceRub, plan.days)
                                            showPaymentOptions = false
                                            statusMessage = "Окно оплаты открыто в браузере. После завершения платежа нажмите «Проверить оплату»."
                                            isSuccessStatus = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = plan.labelRu,
                                                color = MirageColors.Ink,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (!plan.badge.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(MirageColors.PillActiveBg)
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = plan.badge,
                                                        color = MirageColors.Amber,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = "${plan.priceRub} ₽",
                                        color = MirageColors.Amber,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { showPaymentOptions = false },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = MirageColors.Card,
                                contentColor = MirageColors.Ink
                            ),
                            border = BorderStroke(1.dp, MirageColors.CardStroke),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Text(
                                text = "Отмена",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = MirageColors.Ink,
        modifier = Modifier.width(380.dp),
        text = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(MirageColors.Card)
                    .border(1.2.dp, MirageColors.CardStroke, RoundedCornerShape(24.dp))
                    .padding(22.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {

                    // Header Row matching dialog_subscription.xml
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1C2130)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MirageColors.Amber,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mirage Premium",
                                color = MirageColors.Ink,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Выделенные серверы и максимальная скорость",
                                color = MirageColors.InkSoft,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
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

                    // Divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MirageColors.CardStroke)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Price Card matching dialog_subscription.xml
                    val defaultPlan = appConfig.plans.firstOrNull() ?: SubscriptionPlan("1m", 30, 50, null, "30 дней", "30 days")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MirageColors.Bg)
                            .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Период подписки: 30 дней",
                                color = MirageColors.InkSoft,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "от ${defaultPlan.priceRub} ₽",
                                color = MirageColors.Amber,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MirageColors.PillActiveBg)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Хит",
                                color = MirageColors.Amber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Features matching dialog_subscription.xml
                    Text(
                        text = "⚡ Высокоскоростной выделенный канал 1 Гбит/с\n" +
                                "🛡️ Протокол VLESS Reality (обход блокировок ТСПУ)\n" +
                                "🔒 Персональный защищенный ключ доступа\n" +
                                "🌐 Полный доступ к YouTube, зарубежным сервисам и ИИ",
                        color = MirageColors.InkSoft,
                        fontSize = 12.5.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (statusMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = statusMessage ?: "",
                            color = if (isSuccessStatus) Color(0xFF10B981) else MirageColors.InkSoft,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Pay Button
                    Button(
                        onClick = { showPaymentOptions = true },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MirageColors.Amber,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Text(
                            text = "Оформить подписку",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Check Payment Status Button
                    OutlinedButton(
                        onClick = {
                            isChecking = true
                            statusMessage = null
                            scope.launch {
                                SupabaseManager.instance.refreshSubscription()
                                isChecking = false
                                if (SupabaseManager.instance.hasActiveSubscription()) {
                                    statusMessage = "Подписка активна! Доступ к выделенным серверам открыт."
                                    isSuccessStatus = true
                                    onSubscribed()
                                } else {
                                    statusMessage = "Оплата пока не поступила. Повторите попытку через минуту."
                                    isSuccessStatus = false
                                }
                            }
                        },
                        enabled = !isChecking,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = MirageColors.Card,
                            contentColor = MirageColors.Ink
                        ),
                        border = BorderStroke(1.dp, MirageColors.CardStroke),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(
                                color = MirageColors.Ink,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text(
                                text = "Проверить оплату",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // One Free Trial Day Button
                    OutlinedButton(
                        onClick = {
                            trialLoading = true
                            statusMessage = null
                            scope.launch {
                                val devId = DeviceIdRepository().getOrCreateDeviceId()
                                val res = SupabaseManager.instance.requestFreeTrial(devId)
                                trialLoading = false
                                if (res.isSuccess) {
                                    statusMessage = "Пробный день активирован!"
                                    isSuccessStatus = true
                                    onSubscribed()
                                } else {
                                    statusMessage = res.exceptionOrNull()?.message ?: "Пробный день недоступен"
                                    isSuccessStatus = false
                                }
                            }
                        },
                        enabled = !trialLoading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = MirageColors.Card,
                            contentColor = MirageColors.Amber
                        ),
                        border = BorderStroke(1.dp, MirageColors.Amber),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        if (trialLoading) {
                            CircularProgressIndicator(
                                color = MirageColors.Amber,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text(
                                text = "Попробовать бесплатно (1 день)",
                                color = MirageColors.Amber,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Один бесплатный день выделенного узла",
                        color = MirageColors.InkSoft,
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    )
}
