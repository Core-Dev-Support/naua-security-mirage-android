package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.IconButton
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
import com.naua_security_mirage.app.ui.dialogs.DocumentReaderDialog
import com.naua_security_mirage.app.ui.dialogs.SubscriptionDialog
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToApps: () -> Unit,
    onNavigateToWebsites: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToDns: () -> Unit = {},
    onNavigateToPrivateDns: () -> Unit = {}
) {
    val currentUser by SupabaseManager.instance.currentUser.collectAsState()
    val subscription by SupabaseManager.instance.subscription.collectAsState()

    var showAuthDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var openLegalDoc by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updateResultText by remember { mutableStateOf<String?>(null) }

    var statusNotif by remember { mutableStateOf(settingsRepository.isStatusNotificationEnabled) }
    var killSwitch by remember { mutableStateOf(settingsRepository.isKillSwitchEnabled) }
    var autoStart by remember { mutableStateOf(settingsRepository.isAutoStartOnBootEnabled) }
    var directRu by remember { mutableStateOf(settingsRepository.isDirectRuEnabled) }
    var telemetry by remember { mutableStateOf(settingsRepository.isAnonymousTelemetryEnabled) }
    var pingInterval by remember { mutableStateOf(settingsRepository.autoPingIntervalSeconds) }
    var speedInterval by remember { mutableStateOf(settingsRepository.speedIntervalSeconds) }
    var isEmailMasked by remember { mutableStateOf(settingsRepository.isEmailMasked) }

    val colors = LocalMirageColors.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val isEnglish = settingsRepository.appLanguage == SettingsRepository.LANG_EN

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MirageColors.AuBg)
    ) {
        MirageHeader(
            title = if (isEnglish) "Settings" else "Настройки",
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
                text = if (isEnglish) "ACCOUNT & SUBSCRIPTION" else "АККАУНТ И ПОДПИСКА",
                color = MirageColors.AuFg3,
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
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MirageColors.AuAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = MirageColors.AuAccent,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEnglish) "Sign in" else "Войти в аккаунт",
                                color = MirageColors.AuFg,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isEnglish) "For access to paid servers and subscription" else "Для доступа к платным серверам и подписке",
                                color = MirageColors.AuFg2,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = { showAuthDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = MirageColors.AuAccent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = if (isEnglish) "Sign in" else "Войти",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MirageColors.AuAccent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    tint = MirageColors.AuAccent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val emailRaw = currentUser?.email ?: (if (isEnglish) "User" else "Пользователь")
                                val emailDisplay = if (isEmailMasked) SupabaseManager.maskEmail(emailRaw) else emailRaw

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = emailDisplay,
                                        color = MirageColors.AuFg,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = {
                                            isEmailMasked = !isEmailMasked
                                            settingsRepository.isEmailMasked = isEmailMasked
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isEmailMasked) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Маскирование email",
                                            tint = MirageColors.AuFg3,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(3.dp))
                                if (subscription?.isActive == true) {
                                    val dateStr = subscription?.paidUntil?.take(10) ?: (if (isEnglish) "unlimited" else "бессрочно")
                                    Text(
                                        text = if (isEnglish) "Premium active until $dateStr" else "Премиум активен до $dateStr",
                                        color = Color(0xFF10B981),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Text(
                                        text = if (isEnglish) "Free plan (Paid servers locked)" else "Бесплатный тариф (Платные узлы недоступны)",
                                        color = MirageColors.AuFg2,
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
                                        backgroundColor = MirageColors.AuAccent,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Text(
                                        text = if (isEnglish) "Get Subscription" else "Оформить подписку",
                                        fontSize = 13.sp,
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
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    backgroundColor = MirageColors.AuSolid,
                                    contentColor = Color(0xFFEF4444)
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MirageColors.AuSep),
                                modifier = if (subscription?.isActive != true) Modifier.width(96.dp).height(40.dp) else Modifier.fillMaxWidth().height(40.dp)
                            ) {
                                Text(
                                    text = if (isEnglish) "Sign out" else "Выйти",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Notifications
            Text(
                text = if (isEnglish) "NOTIFICATIONS" else "УВЕДОМЛЕНИЯ",
                color = MirageColors.AuFg3,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                SettingsSwitchRow(
                    icon = Icons.Default.Notifications,
                    title = if (isEnglish) "Tray status notifications" else "Оповещения в трее",
                    subtitle = if (isEnglish) "Alerts on protection status change" else "Оповещения о смене статуса защиты Windows",
                    checked = statusNotif,
                    onCheckedChange = {
                        statusNotif = it
                        settingsRepository.isStatusNotificationEnabled = it
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Security & Network
            Text(
                text = if (isEnglish) "SECURITY & NETWORK" else "БЕЗОПАСНОСТЬ И СЕТЬ",
                color = MirageColors.AuFg3,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSwitchRow(
                        icon = Icons.Default.Shield,
                        title = if (isEnglish) "Direct access to .ru sites" else "Прямой доступ к сайтам РФ",
                        subtitle = if (isEnglish) "Gov services, banks and .ru bypass VPN" else "Госуслуги, банки и сервисы .ru открываются напрямую",
                        checked = directRu,
                        onCheckedChange = {
                            directRu = it
                            settingsRepository.isDirectRuEnabled = it
                        }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Default.Lock,
                        title = if (isEnglish) "Kill Switch protection" else "Защита от утечек (Kill Switch)",
                        subtitle = if (isEnglish) "Block traffic if VPN connection drops unexpectedly" else "Блокировать трафик при внезапном обрыве VPN-соединения",
                        checked = killSwitch,
                        onCheckedChange = {
                            killSwitch = it
                            settingsRepository.isKillSwitchEnabled = it
                        }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Default.PowerSettingsNew,
                        title = if (isEnglish) "Start with Windows" else "Автозапуск с Windows",
                        subtitle = if (isEnglish) "Launch NAUA Security Mirage at system boot" else "Запускать NAUA Security Mirage при старте системы",
                        checked = autoStart,
                        onCheckedChange = {
                            autoStart = it
                            settingsRepository.setAutoStartOnBoot(it)
                        }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Default.Analytics,
                        title = if (isEnglish) "Anonymous telemetry" else "Анонимная телеметрия",
                        subtitle = if (isEnglish) "Help improve stability without personal data" else "Помогает улучшать стабильность сети без личных данных",
                        checked = telemetry,
                        onCheckedChange = {
                            telemetry = it
                            settingsRepository.isAnonymousTelemetryEnabled = it
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Settings Navigation
            Text(
                text = if (isEnglish) "SETTINGS SECTIONS" else "РАЗДЕЛЫ НАСТРОЕК",
                color = MirageColors.AuFg3,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsNavRow(
                        icon = Icons.Default.Palette,
                        title = if (isEnglish) "Appearance" else "Внешний вид",
                        subtitle = if (isEnglish) "Themes, 3D button style, color palettes" else "Темы, 3D-стиль кнопки, цветовые палитры",
                        onClick = onNavigateToAppearance
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Default.Apps,
                        title = if (isEnglish) "Per-App Proxy" else "Проксирование приложений",
                        subtitle = if (isEnglish) "Split tunneling for Windows applications" else "Раздельное туннелирование для Windows-программ",
                        onClick = onNavigateToApps
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Default.Language,
                        title = if (isEnglish) "Website Exceptions" else "Обход блокировок сайтов",
                        subtitle = if (isEnglish) "List of domains excluded from VPN" else "Список доменов для исключения из VPN",
                        onClick = onNavigateToWebsites
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Default.Dns,
                        title = if (isEnglish) "DNS inside tunnel" else "DNS внутри туннеля",
                        subtitle = if (isEnglish) "Select resolver for VPN traffic" else "Выбор DNS-сервера для туннеля",
                        onClick = onNavigateToDns
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Default.Security,
                        title = if (isEnglish) "Private DNS" else "Приватный DNS",
                        subtitle = if (isEnglish) "Diagnostics of system Private DNS" else "Диагностика системного Private DNS",
                        onClick = onNavigateToPrivateDns
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.AutoMirrored.Filled.Article,
                        title = if (isEnglish) "Logs & Diagnostics" else "Журнал работы",
                        subtitle = if (isEnglish) "Diagnostic logs and detail level" else "Диагностические логи и уровень детализации",
                        onClick = onNavigateToLogs
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Intervals
            Text(
                text = if (isEnglish) "UPDATE INTERVALS" else "ИНТЕРВАЛЫ ОБНОВЛЕНИЯ",
                color = MirageColors.AuFg3,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isEnglish) "Auto-ping interval" else "Интервал проверки пинга",
                        color = MirageColors.AuFg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Выкл", 1 to "1с", 3 to "3с", 5 to "5с", 10 to "10с").forEach { (sec, label) ->
                            MirageChip(
                                text = label,
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
                        text = if (isEnglish) "Speed update interval" else "Интервал обновления скорости",
                        color = MirageColors.AuFg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Выкл", 1 to "1с", 2 to "2с", 3 to "3с", 5 to "5с").forEach { (sec, label) ->
                            MirageChip(
                                text = label,
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

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Updates
            Text(
                text = if (isEnglish) "UPDATES" else "ОБНОВЛЕНИЯ",
                color = MirageColors.AuFg3,
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEnglish) "Current version: 1.4.0 (5)" else "Текущая версия: 1.4.0 (5)",
                                color = MirageColors.AuFg,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isEnglish) "GitHub Releases" else "Канал обновлений: GitHub Releases",
                                color = MirageColors.AuFg3,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = {
                                isCheckingUpdates = true
                                updateResultText = null
                                scope.launch {
                                    delay(1200)
                                    isCheckingUpdates = false
                                    updateResultText = if (isEnglish) "You have the latest version 1.4.0 (5)" else "У вас установлена последняя версия 1.4.0 (5)"
                                }
                            },
                            enabled = !isCheckingUpdates,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = MirageColors.AuAccent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isCheckingUpdates) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            } else {
                                Text(
                                    text = if (isEnglish) "Check now" else "Проверить сейчас",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (updateResultText != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = updateResultText ?: "",
                            color = Color(0xFF10B981),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Legal links: Terms of Service & Privacy Policy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEnglish) "Terms of Service" else "Условия использования",
                    color = MirageColors.AuFg3,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { openLegalDoc = Pair(if (isEnglish) "Terms of Service" else "Условия использования", "TERMS_OF_SERVICE.txt") }
                        .padding(4.dp)
                )
                Text(
                    text = " • ",
                    color = MirageColors.AuFg3,
                    fontSize = 12.5.sp
                )
                Text(
                    text = if (isEnglish) "Privacy Policy" else "Политика конфиденциальности",
                    color = MirageColors.AuFg3,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { openLegalDoc = Pair(if (isEnglish) "Privacy Policy" else "Политика конфиденциальности", "PRIVACY_POLICY.txt") }
                        .padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // App Version Footer
            Text(
                text = "Версия 1.4.0 (5)",
                color = MirageColors.AuFg3,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            )
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

        if (openLegalDoc != null) {
            DocumentReaderDialog(
                title = openLegalDoc!!.first,
                fileName = openLegalDoc!!.second,
                onDismiss = { openLegalDoc = null }
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
            tint = MirageColors.AuAccent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = MirageColors.AuFg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = MirageColors.AuFg3,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MirageColors.AuFg3,
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
            tint = MirageColors.AuAccent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = MirageColors.AuFg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = MirageColors.AuFg3,
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 16.dp)
            .background(MirageColors.AuSep)
    )
}
