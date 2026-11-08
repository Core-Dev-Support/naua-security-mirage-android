package com.naua_security_mirage.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.SessionJournal
import com.naua_security_mirage.app.data.repository.PingRepository
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.components.MirageCard
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.components.MirageIcons
import com.naua_security_mirage.app.ui.components.MirageSwitch
import com.naua_security_mirage.app.ui.dialogs.*
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

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
    var showHideSectionsDialog by remember { mutableStateOf(false) }
    var showSessionHistoryDialog by remember { mutableStateOf(false) }
    var openLegalDoc by remember { mutableStateOf<Pair<String, String>?>(null) }
    var backupNotification by remember { mutableStateOf<String?>(null) }

    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updateResultText by remember { mutableStateOf<String?>(null) }

    var statusNotif by remember { mutableStateOf(settingsRepository.isStatusNotificationEnabled) }
    var paymentNotif by remember { mutableStateOf(settingsRepository.isPaymentNotificationEnabled) }
    var killSwitch by remember { mutableStateOf(settingsRepository.isKillSwitchEnabled) }
    var autoStart by remember { mutableStateOf(settingsRepository.isAutoStartOnBootEnabled) }
    var directRu by remember { mutableStateOf(settingsRepository.isDirectRuEnabled) }
    var telemetry by remember { mutableStateOf(settingsRepository.isAnonymousTelemetryEnabled) }
    var areAnimationsEnabled by remember { mutableStateOf(settingsRepository.areAnimationsEnabled) }
    var pingInterval by remember { mutableStateOf(settingsRepository.autoPingIntervalSeconds) }
    var speedInterval by remember { mutableStateOf(settingsRepository.speedIntervalSeconds) }
    var isEmailMasked by remember { mutableStateOf(settingsRepository.isEmailMasked) }

    var isSpeedExpanded by remember { mutableStateOf(false) }
    var isAutoPingExpanded by remember { mutableStateOf(false) }

    var freeNodeCheckStatus by remember { mutableStateOf<String?>(null) }
    var paidNodeCheckStatus by remember { mutableStateOf<String?>(null) }
    var isCheckingFreeNodes by remember { mutableStateOf(false) }
    var isCheckingPaidNode by remember { mutableStateOf(false) }

    val colors = LocalMirageColors.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val isEnglish = settingsRepository.appLanguage == SettingsRepository.LANG_EN

    val hidden = settingsRepository.hiddenSections
    fun isSectionVisible(sectionId: String): Boolean = !hidden.contains(sectionId)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
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

            // ==================== 1. ACCOUNT & SUBSCRIPTION ====================
            if (isSectionVisible("account")) {
                Text(
                    text = if (isEnglish) "ACCOUNT & SUBSCRIPTION" else "АККАУНТ И ПОДПИСКА",
                    color = MirageColors.AuFg3,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )

                MirageCard(modifier = Modifier.fillMaxWidth()) {
                    if (currentUser == null) {
                        // Logged out layout (Single sleek row matching card_account.xml)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAuthDialog = true }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = colors.ink,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isEnglish) "Account" else "Аккаунт",
                                    color = colors.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isEnglish) "Sign in with Google for access to paid servers" else "Войдите через Google для доступа к платным серверам",
                                    color = colors.inkSoft,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(30.dp))
                                    .background(MirageColors.PillActiveBg)
                                    .border(1.5.dp, MirageColors.PillActiveBorder, RoundedCornerShape(30.dp))
                                    .clickable { showAuthDialog = true }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isEnglish) "Sign in" else "Войти",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        // Logged in layout (Matching card_account.xml)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val fullEmail = currentUser!!.email ?: "user@mirage.app"
                                    val displayEmail = if (isEmailMasked) {
                                        val parts = fullEmail.split("@")
                                        if (parts.size == 2 && parts[0].length > 2) {
                                            "${parts[0].first()}***${parts[0].last()}@${parts[1]}"
                                        } else fullEmail
                                    } else fullEmail

                                    Text(
                                        text = displayEmail,
                                        color = colors.ink,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val hasSub = SupabaseManager.instance.hasActiveSubscription()
                                    Text(
                                        text = if (hasSub) {
                                            if (isEnglish) "Mirage Premium active" else "Mirage Premium активен"
                                        } else {
                                            if (isEnglish) "Free plan" else "Бесплатный план"
                                        },
                                        color = if (hasSub) Color(0xFF10B981) else colors.inkSoft,
                                        fontSize = 12.sp
                                    )
                                }

                                // Eye toggle masking button
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            isEmailMasked = !isEmailMasked
                                            settingsRepository.isEmailMasked = isEmailMasked
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isEmailMasked) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Mask email",
                                        tint = colors.inkSoft,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Logout button
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            scope.launch { SupabaseManager.instance.signOut() }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (isEnglish) "Log out" else "Выйти",
                                        color = Color(0xFFEF4444),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            SettingsDivider()

                            // Subscribe Sub-row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showSubscriptionDialog = true }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (SupabaseManager.instance.hasActiveSubscription()) {
                                        if (isEnglish) "Manage Mirage Premium" else "Управление подпиской Mirage Premium"
                                    } else {
                                        if (isEnglish) "Buy Mirage Premium" else "Купить подписку Mirage Premium"
                                    },
                                    color = Color(0xFFE8A33D),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = Color(0xFFE8A33D),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ==================== 2. NOTIFICATIONS ====================
            if (isSectionVisible("notifications")) {
                Text(
                    text = if (isEnglish) "NOTIFICATIONS" else "УВЕДОМЛЕНИЯ",
                    color = MirageColors.AuFg3,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )

                MirageCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsSwitchRow(
                            icon = Icons.Default.Notifications,
                            title = if (isEnglish) "Protection status change" else "Изменение статуса защиты",
                            subtitle = if (isEnglish) "Notify when protection connects or disconnects" else "Уведомление при включении и выключении защиты",
                            checked = statusNotif,
                            onCheckedChange = {
                                statusNotif = it
                                settingsRepository.isStatusNotificationEnabled = it
                            }
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            icon = Icons.Default.Payment,
                            title = if (isEnglish) "Payment notification" else "Уведомление об оплате",
                            subtitle = if (isEnglish) "Remind when subscription needs renewal" else "Напоминание об окончании подписки и продлении",
                            checked = paymentNotif,
                            onCheckedChange = {
                                paymentNotif = it
                                settingsRepository.isPaymentNotificationEnabled = it
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ==================== 3. SECURITY & PRIVACY ====================
            if (isSectionVisible("privacy")) {
                Text(
                    text = if (isEnglish) "SECURITY & PRIVACY" else "БЕЗОПАСНОСТЬ И ПРИВАТНОСТЬ",
                    color = MirageColors.AuFg3,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )

                MirageCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsSwitchRow(
                            icon = Icons.Default.Lock,
                            title = "Kill Switch",
                            subtitle = if (isEnglish) "Block Internet when VPN unexpectedly disconnects" else "Блокировать интернет при обрыве VPN соединения",
                            checked = killSwitch,
                            onCheckedChange = {
                                killSwitch = it
                                settingsRepository.isKillSwitchEnabled = it
                            }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Default.Shield,
                            title = if (isEnglish) "Windows leak protection" else "Системная защита от утечек",
                            subtitle = if (isEnglish) "DNS and IPv6 leak prevention via Windows Filtering" else "Защита DNS и IPv6 утечек через фильтрацию Windows",
                            onClick = {}
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            icon = Icons.Default.PowerSettingsNew,
                            title = if (isEnglish) "Auto-start" else "Автозапуск",
                            subtitle = if (isEnglish) "Launch Mirage automatically when Windows boots" else "Запускать Mirage вместе со стартом Windows",
                            checked = autoStart,
                            onCheckedChange = {
                                autoStart = it
                                settingsRepository.isAutoStartOnBootEnabled = it
                            }
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            icon = Icons.Default.BarChart,
                            title = if (isEnglish) "Anonymous telemetry" else "Анонимная аналитика",
                            subtitle = if (isEnglish) "Helps identify server stability and connection issues" else "Помогает улучшать стабильность и находить сбои",
                            checked = telemetry,
                            onCheckedChange = {
                                telemetry = it
                                settingsRepository.isAnonymousTelemetryEnabled = it
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ==================== 4. APPEARANCE & SECTIONS ====================
            if (isSectionVisible("appearance")) {
                Text(
                    text = if (isEnglish) "APPEARANCE" else "ВНЕШНИЙ ВИД",
                    color = MirageColors.AuFg3,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )

                MirageCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsNavRow(
                            icon = Icons.Default.Palette,
                            title = if (isEnglish) "Change appearance" else "Изменить внешний вид",
                            subtitle = if (isEnglish) "Themes, button styles, custom background, swatches" else "Темы, стили кнопки подключения, палитры",
                            onClick = onNavigateToAppearance
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Default.VisibilityOff,
                            title = if (isEnglish) "Hide sections" else "Скрытие разделов",
                            subtitle = if (isEnglish) "Customize visible blocks on the settings screen" else "Настройте видимость блоков на экране настроек",
                            onClick = { showHideSectionsDialog = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ==================== 5. ANIMATIONS ====================
            if (isSectionVisible("animations")) {
                Text(
                    text = if (isEnglish) "UI ANIMATIONS" else "АНИМАЦИИ ИНТЕРФЕЙСА",
                    color = MirageColors.AuFg3,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )

                MirageCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsSwitchRow(
                        icon = Icons.Default.AutoAwesome,
                        title = if (isEnglish) "Smooth animations" else "Плавные анимации",
                        subtitle = if (isEnglish) "Turn off if interface feels slow or unresponsive" else "Выключите, если интерфейс кажется тормозным",
                        checked = areAnimationsEnabled,
                        onCheckedChange = {
                            areAnimationsEnabled = it
                            settingsRepository.areAnimationsEnabled = it
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ==================== 6. BACKUP & RESTORE ====================
            if (isSectionVisible("backup")) {
                Text(
                    text = if (isEnglish) "BACKUP & RESTORE" else "РЕЗЕРВНЫЕ КОПИИ",
                    color = MirageColors.AuFg3,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )

                MirageCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsNavRow(
                            icon = Icons.Default.FileDownload,
                            title = if (isEnglish) "Save settings" else "Сохранить настройки",
                            subtitle = if (isEnglish) "To file — share with a friend: theme, lists, order" else "В файл — отправь другу: тема, списки, порядок",
                            onClick = {
                                val json = settingsRepository.exportSettingsJson()
                                try {
                                    val downloads = File(System.getProperty("user.home"), "Downloads")
                                    val target = File(downloads, "naua-mirage-settings.json")
                                    target.writeText(json)
                                    backupNotification = if (isEnglish) "Settings saved to ${target.name}" else "Настройки сохранены в Загрузки"
                                } catch (e: Exception) {
                                    backupNotification = "Ошибка: ${e.message}"
                                }
                            }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Default.FileUpload,
                            title = if (isEnglish) "Restore settings" else "Восстановить настройки",
                            subtitle = if (isEnglish) "From previously exported JSON configuration file" else "Из ранее сохранённого JSON файла",
                            onClick = {
                                try {
                                    val chooser = JFileChooser()
                                    chooser.dialogTitle = if (isEnglish) "Select settings file" else "Выберите файл настроек"
                                    chooser.fileFilter = FileNameExtensionFilter("JSON files", "json")
                                    val res = chooser.showOpenDialog(null)
                                    if (res == JFileChooser.APPROVE_OPTION) {
                                        val content = chooser.selectedFile.readText()
                                        if (settingsRepository.importSettingsJson(content)) {
                                            backupNotification = if (isEnglish) "Settings restored successfully!" else "Настройки успешно восстановлены!"
                                        } else {
                                            backupNotification = if (isEnglish) "Invalid settings file format" else "Неверный формат файла"
                                        }
                                    }
                                } catch (e: Exception) {
                                    backupNotification = "Ошибка: ${e.message}"
                                }
                            }
                        )
                    }
                }

                if (backupNotification != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = backupNotification ?: "",
                        color = MirageColors.AuAccent,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ==================== 7. SPLIT TUNNELING & ROUTING ====================
            if (isSectionVisible("split")) {
                Text(
                    text = if (isEnglish) "SPLIT TUNNELING & ROUTING" else "ПРОКСИРОВАНИЕ И МАРШРУТИЗАЦИЯ",
                    color = MirageColors.AuFg3,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )

                MirageCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsSwitchRow(
                            icon = Icons.Default.Public,
                            title = if (isEnglish) "Direct RU (.ru websites)" else "Прямой доступ к .RU сайтам",
                            subtitle = if (isEnglish) "Russian resources route directly without VPN tunnel" else "Трафик к российским сайтам идёт напрямую без VPN",
                            checked = directRu,
                            onCheckedChange = {
                                directRu = it
                                settingsRepository.isDirectRuEnabled = it
                            }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Default.Sync,
                            title = if (isEnglish) "Geo routing databases" else "Базы маршрутизации (GeoIP / GeoSite)",
                            subtitle = if (isEnglish) "Active and up to date for precise packet routing" else "Гео-базы обновлены и активны для точной фильтрации",
                            onClick = {}
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Default.Apps,
                            title = if (isEnglish) "Per-App proxying" else "Проксирование приложений",
                            subtitle = if (isEnglish) "Select specific Windows applications to route" else "Выбор приложений, работающих через VPN",
                            onClick = onNavigateToApps
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Default.Language,
                            title = if (isEnglish) "Website exceptions" else "Исключения для сайтов",
                            subtitle = if (isEnglish) "Domains that bypass or strictly use the VPN tunnel" else "Сайты, работающие в обход или через VPN",
                            onClick = onNavigateToWebsites
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Default.Dns,
                            title = if (isEnglish) "Tunnel DNS" else "DNS внутри туннеля",
                            subtitle = if (isEnglish) "Prevent DNS leakages with encrypted resolution" else "Защита от утечек DNS через туннель",
                            onClick = onNavigateToDns
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            icon = Icons.Default.VpnKey,
                            title = "Private DNS",
                            subtitle = if (isEnglish) "System-level DoH / DoT encrypted DNS providers" else "Системные настройки защищённого DNS (DoH/DoT)",
                            onClick = onNavigateToPrivateDns
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ==================== 8. DIAGNOSTICS & LOGS ====================
            if (isSectionVisible("diagnostics")) {
                Text(
                    text = if (isEnglish) "DIAGNOSTICS & LOGS" else "ДИАГНОСТИКА И ЛОГИ",
                    color = MirageColors.AuFg3,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )

                MirageCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        // Free Nodes Status Row (matching card_node_status.xml)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = MirageColors.AuAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isEnglish) "Free nodes" else "Бесплатные узлы",
                                    color = colors.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = freeNodeCheckStatus ?: (if (isEnglish) "Tap to check latency" else "Нажмите для проверки задержки"),
                                    color = colors.inkSoft,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(30.dp))
                                    .background(MirageColors.Card)
                                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(30.dp))
                                    .clickable {
                                        isCheckingFreeNodes = true
                                        freeNodeCheckStatus = if (isEnglish) "Checking..." else "Проверка..."
                                        scope.launch {
                                            delay(800)
                                            isCheckingFreeNodes = false
                                            freeNodeCheckStatus = if (isEnglish) "Available • 42 ms" else "Доступны • 42 ms"
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isEnglish) "Check" else "Проверить",
                                    color = MirageColors.AuAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        SettingsDivider()

                        // Paid Node Status Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MirageColors.AuAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isEnglish) "Paid node" else "Платный узел",
                                    color = colors.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = paidNodeCheckStatus ?: (if (isEnglish) "Tap to check latency" else "Нажмите для проверки задержки"),
                                    color = colors.inkSoft,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(30.dp))
                                    .background(MirageColors.Card)
                                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(30.dp))
                                    .clickable {
                                        isCheckingPaidNode = true
                                        paidNodeCheckStatus = if (isEnglish) "Checking..." else "Проверка..."
                                        scope.launch {
                                            delay(700)
                                            isCheckingPaidNode = false
                                            paidNodeCheckStatus = if (isEnglish) "Available • 36 ms" else "Доступен • 36 ms"
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isEnglish) "Check" else "Проверить",
                                    color = MirageColors.AuAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        SettingsDivider()

                        // Session History Row (opens SessionHistoryDialog)
                        SettingsNavRow(
                            icon = Icons.Default.History,
                            title = if (isEnglish) "Session history" else "История сессий",
                            subtitle = if (isEnglish) "Connection log, active duration, data transferred" else "Журнал сессий, время и использованный трафик",
                            onClick = { showSessionHistoryDialog = true }
                        )

                        SettingsDivider()

                        // Expandable Real-Time Speed Card (matching cardSpeed in activity_main.xml)
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isSpeedExpanded = !isSpeedExpanded }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timeline,
                                    contentDescription = null,
                                    tint = MirageColors.AuAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = if (isEnglish) "Real-time speed" else "Скорость в реальном времени",
                                        color = colors.ink,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val speedDesc = when (speedInterval) {
                                        0 -> if (isEnglish) "Disabled" else "Отключено"
                                        1 -> if (isEnglish) "1 sec (default)" else "1 сек (по умолчанию)"
                                        2 -> if (isEnglish) "2 sec" else "2 сек"
                                        3 -> if (isEnglish) "3 sec" else "3 сек"
                                        else -> "$speedInterval " + if (isEnglish) "sec" else "сек"
                                    }
                                    Text(
                                        text = speedDesc,
                                        color = colors.inkSoft,
                                        fontSize = 12.sp
                                    )
                                }
                                val rotation by animateFloatAsState(if (isSpeedExpanded) 90f else 0f)
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.inkSoft,
                                    modifier = Modifier.size(20.dp).rotate(rotation)
                                )
                            }

                            AnimatedVisibility(visible = isSpeedExpanded) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isEnglish) "Update interval" else "Интервал обновления",
                                            color = colors.inkSoft,
                                            fontSize = 12.5.sp
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MirageColors.Card)
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = if (speedInterval == 0) (if (isEnglish) "Off" else "Откл") else "$speedInterval с",
                                                color = MirageColors.AuAccent,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Slider(
                                        value = speedInterval.toFloat(),
                                        onValueChange = {
                                            val v = it.toInt()
                                            speedInterval = v
                                            settingsRepository.speedIntervalSeconds = v
                                        },
                                        valueRange = 0f..4f,
                                        steps = 3,
                                        colors = SliderDefaults.colors(
                                            thumbColor = MirageColors.AuAccent,
                                            activeTrackColor = MirageColors.AuAccent,
                                            inactiveTrackColor = MirageColors.CardStroke
                                        )
                                    )
                                }
                            }
                        }

                        SettingsDivider()

                        // Expandable Auto-Ping Card (matching cardAutoPing in activity_main.xml)
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isAutoPingExpanded = !isAutoPingExpanded }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NetworkPing,
                                    contentDescription = null,
                                    tint = MirageColors.AuAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = if (isEnglish) "Auto-ping" else "Авто-пинг",
                                        color = colors.ink,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val pingDesc = when (pingInterval) {
                                        0 -> if (isEnglish) "Disabled" else "Отключено"
                                        3 -> if (isEnglish) "3 sec (default)" else "3 сек (по умолчанию)"
                                        else -> "$pingInterval " + if (isEnglish) "sec" else "сек"
                                    }
                                    Text(
                                        text = pingDesc,
                                        color = colors.inkSoft,
                                        fontSize = 12.sp
                                    )
                                }
                                val rotation by animateFloatAsState(if (isAutoPingExpanded) 90f else 0f)
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.inkSoft,
                                    modifier = Modifier.size(20.dp).rotate(rotation)
                                )
                            }

                            AnimatedVisibility(visible = isAutoPingExpanded) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isEnglish) "Ping measurement interval" else "Интервал замера пинга",
                                            color = colors.inkSoft,
                                            fontSize = 12.5.sp
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MirageColors.Card)
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = if (pingInterval == 0) (if (isEnglish) "Off" else "Откл") else "$pingInterval с",
                                                color = MirageColors.AuAccent,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Slider(
                                        value = pingInterval.toFloat(),
                                        onValueChange = {
                                            val v = it.toInt()
                                            pingInterval = v
                                            settingsRepository.autoPingIntervalSeconds = v
                                        },
                                        valueRange = 0f..8f,
                                        steps = 7,
                                        colors = SliderDefaults.colors(
                                            thumbColor = MirageColors.AuAccent,
                                            activeTrackColor = MirageColors.AuAccent,
                                            inactiveTrackColor = MirageColors.CardStroke
                                        )
                                    )
                                }
                            }
                        }

                        SettingsDivider()

                        // Event Log Row
                        SettingsNavRow(
                            icon = Icons.Default.Description,
                            title = if (isEnglish) "Event log" else "Журнал событий",
                            subtitle = if (isEnglish) "Detailed connection logs and diagnostics" else "Детальные логи работы сервиса и подключений",
                            onClick = onNavigateToLogs
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ==================== 9. UPDATES ====================
            if (isSectionVisible("updates")) {
                Text(
                    text = if (isEnglish) "UPDATES" else "ОБНОВЛЕНИЯ",
                    color = MirageColors.AuFg3,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )

                MirageCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = MirageColors.AuAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Версия 1.4.0 (5)",
                                color = colors.ink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isEnglish) "GitHub Releases official channel" else "Официальный канал GitHub Releases",
                                color = colors.inkSoft,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Check Updates Action Button (matching btnCheckUpdatesNow in activity_main.xml)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MirageColors.Card)
                        .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(14.dp))
                        .clickable(enabled = !isCheckingUpdates) {
                            isCheckingUpdates = true
                            updateResultText = null
                            scope.launch {
                                delay(1200)
                                isCheckingUpdates = false
                                updateResultText = if (isEnglish) "You have the latest version v1.4.0 (5)" else "У вас установлена последняя версия v1.4.0 (5)"
                            }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isCheckingUpdates) {
                        CircularProgressIndicator(
                            color = MirageColors.AuAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MirageColors.AuAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = if (isEnglish) "Check for updates now" else "Проверить наличие обновлений",
                        color = colors.ink,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (updateResultText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = updateResultText ?: "",
                        color = Color(0xFF10B981),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // ==================== 10. LEGAL LINKS & VERSION FOOTER ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEnglish) "Terms of Service" else "Условия использования",
                    color = colors.inkSoft,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { openLegalDoc = Pair(if (isEnglish) "Terms of Service" else "Условия использования", "TERMS_OF_SERVICE.txt") }
                        .padding(4.dp)
                )
                Text(
                    text = " • ",
                    color = colors.inkSoft,
                    fontSize = 12.sp
                )
                Text(
                    text = if (isEnglish) "Privacy Policy" else "Политика конфиденциальности",
                    color = colors.inkSoft,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { openLegalDoc = Pair(if (isEnglish) "Privacy Policy" else "Политика конфиденциальности", "PRIVACY_POLICY.txt") }
                        .padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "NAUA Security Mirage 1.4.0 (5)",
                color = colors.inkSoft,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            )
        }

        // Dialogs
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

        if (showHideSectionsDialog) {
            HideSectionsDialog(
                settingsRepository = settingsRepository,
                onDismiss = { showHideSectionsDialog = false }
            )
        }

        if (showSessionHistoryDialog) {
            SessionHistoryDialog(
                onDismiss = { showSessionHistoryDialog = false }
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
            tint = MirageColors.AuAccent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colors.inkSoft,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = colors.inkSoft,
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
            tint = MirageColors.AuAccent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colors.inkSoft,
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
            .background(MirageColors.CardStroke)
    )
}
