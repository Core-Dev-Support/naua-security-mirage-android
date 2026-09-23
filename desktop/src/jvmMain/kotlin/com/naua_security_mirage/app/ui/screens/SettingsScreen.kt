package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.components.MirageCard
import com.naua_security_mirage.app.ui.components.MirageChip
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.components.MirageSwitch
import com.naua_security_mirage.app.ui.dialogs.AuthDialog
import com.naua_security_mirage.app.ui.dialogs.SubscriptionDialog
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToApps: () -> Unit,
    onNavigateToWebsites: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val colors = LocalMirageColors.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val currentUser by SupabaseManager.instance.currentUser.collectAsState()
    val subscription by SupabaseManager.instance.subscription.collectAsState()

    var showAuthDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }

    var statusNotif by remember { mutableStateOf(settingsRepository.isStatusNotificationEnabled) }
    var killSwitch by remember { mutableStateOf(settingsRepository.isKillSwitchEnabled) }
    var autoStart by remember { mutableStateOf(settingsRepository.isAutoStartOnBootEnabled) }
    var directRu by remember { mutableStateOf(settingsRepository.isDirectRuEnabled) }
    var pingInterval by remember { mutableStateOf(settingsRepository.autoPingIntervalSeconds) }
    var speedInterval by remember { mutableStateOf(settingsRepository.speedIntervalSeconds) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        MirageHeader(
            title = "Настройки",
            isSubScreen = true,
            onBackClick = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            // Section: Account & Subscription
            Text(
                text = "АККАУНТ И ПОДПИСКА",
                color = colors.inkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                if (currentUser == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Войти в аккаунт",
                                color = colors.ink,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Для доступа к серверу Франция и подписке",
                                color = colors.inkSoft,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = { showAuthDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = colors.accent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Войти",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(42.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentUser?.email ?: "Пользователь",
                                    color = colors.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                if (subscription?.isActive == true) {
                                    val dateStr = subscription?.paidUntil?.take(10) ?: "бессрочно"
                                    Text(
                                        text = "Премиум активен до $dateStr",
                                        color = Color(0xFF10B981),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Text(
                                        text = "Бесплатный тариф (Франция недоступна)",
                                        color = colors.inkSoft,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (subscription?.isActive != true) {
                                Button(
                                    onClick = { showSubscriptionDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = colors.accent,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(38.dp)
                                ) {
                                    Text(
                                        text = "Оформить подписку",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        SupabaseManager.instance.signOut()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    backgroundColor = colors.card,
                                    contentColor = Color(0xFFEF4444)
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardStroke),
                                modifier = if (subscription?.isActive != true) Modifier.width(90.dp).height(38.dp) else Modifier.fillMaxWidth().height(38.dp)
                            ) {
                                Text(
                                    text = "Выйти",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            // Section: Sub-screens
            Text(
                text = "РАЗДЕЛЫ НАСТРОЕК",
                color = colors.inkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsNavRow(
                        icon = Icons.Default.Palette,
                        title = "Внешний вид",
                        subtitle = "Темы, 3D стиль кнопки, цветовые палитры",
                        onClick = onNavigateToAppearance
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Default.Apps,
                        title = "Проксирование приложений",
                        subtitle = "Раздельное туннелирование для Windows программ",
                        onClick = onNavigateToApps
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Default.Language,
                        title = "Обход блокировок сайтов",
                        subtitle = "Список доменов для исключения из VPN",
                        onClick = onNavigateToWebsites
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Default.Article,
                        title = "Журнал работы",
                        subtitle = "Диагностические логи и уровень детализации",
                        onClick = onNavigateToLogs
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Network & Security
            Text(
                text = "СЕТЬ И БЕЗОПАСНОСТЬ",
                color = colors.inkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSwitchRow(
                        icon = Icons.Default.Shield,
                        title = "Прямой доступ к сайтам РФ",
                        subtitle = "Госуслуги, банки и сервисы .ru открываются напрямую",
                        checked = directRu,
                        onCheckedChange = {
                            directRu = it
                            settingsRepository.isDirectRuEnabled = it
                        }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Default.Lock,
                        title = "Защита от утечек (Kill Switch)",
                        subtitle = "Блокировать трафик при внезапном обрыве VPN соединения",
                        checked = killSwitch,
                        onCheckedChange = {
                            killSwitch = it
                            settingsRepository.isKillSwitchEnabled = it
                        }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Default.Notifications,
                        title = "Уведомления в трее",
                        subtitle = "Оповещения о смене статуса защиты Windows",
                        checked = statusNotif,
                        onCheckedChange = {
                            statusNotif = it
                            settingsRepository.isStatusNotificationEnabled = it
                        }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Default.PowerSettingsNew,
                        title = "Автозапуск с Windows",
                        subtitle = "Запускать NAUA Security Mirage при старте системы",
                        checked = autoStart,
                        onCheckedChange = {
                            autoStart = it
                            settingsRepository.isAutoStartOnBootEnabled = it
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Timing & Intervals
            Text(
                text = "ИНТЕРВАЛЫ ОБНОВЛЕНИЯ",
                color = colors.inkFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Интервал проверки пинга",
                        color = colors.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 3, 5, 10).forEach { sec ->
                            MirageChip(
                                text = "${sec} сек",
                                selected = pingInterval == sec,
                                onClick = {
                                    pingInterval = sec
                                    settingsRepository.autoPingIntervalSeconds = sec
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Интервал обновления скорости",
                        color = colors.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3, 5).forEach { sec ->
                            MirageChip(
                                text = "${sec} сек",
                                selected = speedInterval == sec,
                                onClick = {
                                    speedInterval = sec
                                    settingsRepository.speedIntervalSeconds = sec
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (showAuthDialog) {
            AuthDialog(
                onDismiss = { showAuthDialog = false },
                onSuccess = {
                    showAuthDialog = false
                    scope.launch { SupabaseManager.instance.refreshSubscription() }
                }
            )
        }

        if (showSubscriptionDialog && currentUser != null) {
            SubscriptionDialog(
                userId = currentUser!!.id,
                onDismiss = { showSubscriptionDialog = false },
                onSubscribed = {
                    showSubscriptionDialog = false
                    scope.launch { SupabaseManager.instance.refreshSubscription() }
                }
            )
        }
    }
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = LocalMirageColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colors.inkMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = colors.inkMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalMirageColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colors.inkMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        MirageSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsDivider() {
    val colors = LocalMirageColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 16.dp)
            .background(colors.cardStroke)
    )
}
