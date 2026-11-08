package com.naua_security_mirage.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.config.AppConfigRepository
import com.naua_security_mirage.app.data.config.PaidNode
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.components.MirageIcons
import com.naua_security_mirage.app.ui.dialogs.AuthDialog
import com.naua_security_mirage.app.ui.dialogs.SubscriptionDialog
import com.naua_security_mirage.app.ui.dialogs.SupportWaysDialog
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors
import com.naua_security_mirage.app.ui.view.Mirage3DCanvas
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

@Composable
fun MainScreen(
    vpnState: VpnState,
    currentServer: VlessServer?,
    downloadSpeed: String,
    uploadSpeed: String,
    settingsRepository: SettingsRepository,
    selectedPlan: String,
    onSelectPlan: (String) -> Unit,
    sessionSeconds: Long = 0L,
    isRefreshing: Boolean = false,
    hasUpdate: Boolean = false,
    onToggleConnect: () -> Unit,
    onRefreshServers: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = LocalMirageColors.current
    val scope = rememberCoroutineScope()
    val currentUser by SupabaseManager.instance.currentUser.collectAsState()
    val appConfig by AppConfigRepository.config.collectAsState()

    var showUpdateDialog by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showSupportWaysDialog by remember { mutableStateOf(false) }
    var showTelegramWaysDialog by remember { mutableStateOf(false) }
    var showPaidPickerPopup by remember { mutableStateOf(false) }

    val isConnected = vpnState == VpnState.CONNECTED
    val isConnecting = vpnState == VpnState.CONNECTING
    val isEnglish = settingsRepository.appLanguage == SettingsRepository.LANG_EN

    val infiniteTransition = rememberInfiniteTransition()
    val glowScale by infiniteTransition.animateFloat(
        initialValue = if (isConnecting) 0.95f else 1.0f,
        targetValue = if (isConnecting) 1.06f else if (isConnected) 1.03f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnecting) 800 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = if (isConnecting) 0.3f else if (isConnected) 0.5f else 0.0f,
        targetValue = if (isConnecting) 0.9f else if (isConnected) 0.8f else 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnecting) 800 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val currentPaidNode = appConfig.paidNodes.firstOrNull { it.id == selectedPlan }
        ?: appConfig.paidNodes.firstOrNull { it.id == settingsRepository.lastPaidPlan }
        ?: appConfig.paidNodes.firstOrNull()

    val isPaidActive = selectedPlan != SettingsRepository.PLAN_FREE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MirageColors.AuBg)
    ) {
        MirageHeader(
            title = "Mirage",
            subtitle = "NAUA Security",
            isSubScreen = false,
            isRefreshing = isRefreshing,
            hasUpdate = hasUpdate,
            languageText = settingsRepository.appLanguage.uppercase(),
            onLanguageClick = {
                settingsRepository.appLanguage = if (settingsRepository.appLanguage == SettingsRepository.LANG_RU) {
                    SettingsRepository.LANG_EN
                } else {
                    SettingsRepository.LANG_RU
                }
            },
            onUpdateClick = { showUpdateDialog = true },
            onRefreshClick = onRefreshServers,
            onSettingsClick = onOpenSettings
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(26.dp))

            // Connect Button with Glow (Radial gradient matching Android bg_connect_btn.xml)
            Box(
                modifier = Modifier.size(214.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isConnected || isConnecting) {
                    Box(
                        modifier = Modifier
                            .size(214.dp)
                            .scale(glowScale)
                            .clip(CircleShape)
                            .background(MirageColors.GlowAmberRing.copy(alpha = glowAlpha))
                            .border(2.dp, MirageColors.GlowAmber.copy(alpha = glowAlpha), CircleShape)
                    )
                }

                val isStandardStyle = settingsRepository.connectBtnStyle == SettingsRepository.STYLE_STANDARD
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(CircleShape)
                        .then(
                            if (isStandardStyle) {
                                Modifier.background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            MirageColors.AmberLight,
                                            MirageColors.Amber,
                                            MirageColors.AmberDeep
                                        ),
                                        center = Offset(180f * 0.38f * 1.5f, 180f * 0.32f * 1.5f),
                                        radius = 180f * 1.5f * 0.55f
                                    )
                                )
                            } else Modifier
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onToggleConnect()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isStandardStyle) {
                        MirageIcons.PowerIcon(
                            modifier = Modifier.size(44.dp),
                            tint = if (isConnected) MirageColors.PowerIconConnected else Color(0xFF2A1706)
                        )
                    } else {
                        Mirage3DCanvas(
                            style = settingsRepository.connectBtnStyle,
                            vpnState = vpnState,
                            onToggleConnect = onToggleConnect,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Status Text
            val statusText = when (vpnState) {
                VpnState.DISCONNECTED -> if (isEnglish) "Disconnected" else "Отключено"
                VpnState.CONNECTING -> if (isEnglish) "Connecting..." else "Подключение..."
                VpnState.CONNECTED -> if (isEnglish) "Connected" else "Подключено"
                VpnState.DISCONNECTING -> if (isEnglish) "Disconnecting..." else "Отключение..."
            }
            Text(
                text = statusText,
                color = MirageColors.AuFg,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 22.dp)
            )

            // Status Subtext
            val statusSubText = when (vpnState) {
                VpnState.DISCONNECTED -> if (isEnglish) "Tap to enable protection" else "Нажми, чтобы включить защиту"
                VpnState.CONNECTING -> if (isEnglish) "Finding best server..." else "Поиск наилучшего сервера..."
                VpnState.CONNECTED -> {
                    val minutes = sessionSeconds / 60
                    val remSeconds = sessionSeconds % 60
                    val timeStr = String.format("%02d:%02d", minutes, remSeconds)
                    val pingDisplay = currentServer?.pingMs?.takeIf { it in 1..9998 } ?: 45L
                    if (isEnglish) "Session time $timeStr • $pingDisplay ms" else "Время сессии $timeStr • $pingDisplay ms"
                }
                VpnState.DISCONNECTING -> if (isEnglish) "Disconnecting..." else "Отключение..."
            }
            Text(
                text = statusSubText,
                color = MirageColors.AuFg2,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 5.dp)
            )

            // Server Plan Selector (Matching layout_server_plan.xml 1-to-1)
            Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(MirageColors.Card)
                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(30.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Free Segment (No emoji globe, text only matching Android R.string.plan_free)
                val isFree = !isPaidActive
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(30.dp))
                        .background(if (isFree) MirageColors.PillActiveBg else Color.Transparent)
                        .border(
                            width = if (isFree) 1.5.dp else 0.dp,
                            color = if (isFree) MirageColors.PillActiveBorder else Color.Transparent,
                            shape = RoundedCornerShape(30.dp)
                        )
                        .clickable { onSelectPlan(SettingsRepository.PLAN_FREE) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isEnglish) "Free" else "Бесплатный",
                        color = if (isFree) Color.White else colors.inkSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Paid Segment with Vector Flag and Dropdown Arrow
                val paidLabel = currentPaidNode?.label(isEnglish)
                    ?: (if (isEnglish) "France (Paid)" else "Франция (Платная)")

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(30.dp))
                        .background(if (isPaidActive) MirageColors.PillActiveBg else Color.Transparent)
                        .border(
                            width = if (isPaidActive) 1.5.dp else 0.dp,
                            color = if (isPaidActive) MirageColors.PillActiveBorder else Color.Transparent,
                            shape = RoundedCornerShape(30.dp)
                        )
                        .clickable {
                            if (currentUser == null) {
                                showAuthDialog = true
                            } else if (!SupabaseManager.instance.hasActiveSubscription()) {
                                showSubscriptionDialog = true
                            } else {
                                onSelectPlan(currentPaidNode?.id ?: SettingsRepository.PLAN_PREMIUM_FRANCE)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MirageIcons.Flag(
                            flag = currentPaidNode?.flag ?: "france",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = paidLabel,
                            color = if (isPaidActive) Color.White else colors.inkSoft,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { showPaidPickerPopup = true }
                                .padding(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Выбор узла",
                                tint = if (isPaidActive) Color.White else colors.inkSoft,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Real-Time Speed Display (visible when connected)
            AnimatedVisibility(
                visible = isConnected,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(MirageColors.Card)
                        .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(30.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = MirageColors.AuFg3,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = downloadSpeed,
                        color = MirageColors.AuFg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(10.dp)
                            .background(MirageColors.CardStroke)
                    )
                    Spacer(modifier = Modifier.width(10.dp))

                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = MirageColors.AuFg3,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = uploadSpeed,
                        color = MirageColors.AuFg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Support Project Button (Centered pill wrap_content, matching Android btnSupport)
            Row(
                modifier = Modifier
                    .height(46.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(MirageColors.Card)
                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(30.dp))
                    .clickable { showSupportWaysDialog = true }
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                MirageIcons.Heart(
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFFEF4444)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isEnglish) "Support the project" else "Поддержать проект",
                    color = MirageColors.AuFg,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Telegram Button (Centered pill wrap_content, matching Android btnTelegram)
            Row(
                modifier = Modifier
                    .height(46.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(MirageColors.Card)
                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(30.dp))
                    .clickable { showTelegramWaysDialog = true }
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                MirageIcons.TelegramPlane(
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF38BDF8)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isEnglish) "Telegram: channel, bot, support" else "Telegram: канал, бот, поддержка",
                    color = MirageColors.AuFg,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Hint Card (18dp corner, matching Android containerHint)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 540.dp)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MirageColors.Card)
                    .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = colors.inkSoft,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = buildAnnotatedString {
                        if (isEnglish) {
                            append("Mirage automatically selects the server.\n\n")
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = MirageColors.AuFg)) {
                                append("No location picking required.")
                            }
                            append("\nJust tap the connect button.")
                        } else {
                            append("Mirage сам выбирает сервер.\n\n")
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = MirageColors.AuFg)) {
                                append("Выбор локации не нужен.")
                            }
                            append("\nПросто нажми кнопку подключения.")
                        }
                    },
                    color = MirageColors.AuFg2,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }

    // Popup for Paid Server Picker (Chevron menu)
    if (showPaidPickerPopup) {
        AlertDialog(
            onDismissRequest = { showPaidPickerPopup = false },
            buttons = {},
            backgroundColor = Color.Transparent,
            contentColor = colors.ink,
            modifier = Modifier.width(320.dp),
            text = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.card)
                        .border(1.dp, colors.cardStroke, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = if (isEnglish) "Select Paid Server" else "Выбор платного узла",
                            color = colors.ink,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            appConfig.paidNodes.forEach { node ->
                                val isSelected = node.id == selectedPlan || (selectedPlan == SettingsRepository.PLAN_FREE && node.id == settingsRepository.lastPaidPlan)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) MirageColors.PillActiveBg else colors.bg)
                                        .border(
                                            width = if (isSelected) 1.dp else 0.dp,
                                            color = if (isSelected) MirageColors.PillActiveBorder else Color.Transparent,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            showPaidPickerPopup = false
                                            settingsRepository.lastPaidPlan = node.id
                                            if (currentUser == null) {
                                                showAuthDialog = true
                                            } else if (!SupabaseManager.instance.hasActiveSubscription()) {
                                                showSubscriptionDialog = true
                                            } else {
                                                onSelectPlan(node.id)
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MirageIcons.Flag(
                                        flag = node.flag,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = node.label(isEnglish),
                                        color = if (isSelected) MirageColors.AuAccent else colors.ink,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Text(
                                            text = "✓",
                                            color = MirageColors.AuAccent,
                                            fontSize = 15.sp,
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

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = {
                Text(
                    text = "NAUA Security Mirage PC",
                    color = MirageColors.AuFg,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (hasUpdate) {
                        "Доступно обновление для Windows! Нажмите «Скачать», чтобы перейти к загрузке новой версии."
                    } else {
                        "У вас установлена актуальная версия v1.4.0 (5).\nВсе сервера и модули защиты обновлены."
                    },
                    color = MirageColors.AuFg2,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUpdateDialog = false
                        try {
                            Desktop.getDesktop().browse(URI("https://github.com/Core-Dev-Support/naua-security-mirage-android/releases/latest"))
                        } catch (_: Exception) {}
                    }
                ) {
                    Text(
                        text = if (hasUpdate) "Скачать" else "ОК",
                        color = MirageColors.AuAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            backgroundColor = MirageColors.Card,
            contentColor = MirageColors.AuFg
        )
    }

    if (showAuthDialog) {
        AuthDialog(
            onDismiss = { showAuthDialog = false },
            onSuccess = {
                showAuthDialog = false
                scope.launch {
                    SupabaseManager.instance.refreshSubscription()
                    if (SupabaseManager.instance.hasActiveSubscription()) {
                        onSelectPlan(currentPaidNode?.id ?: SettingsRepository.PLAN_PREMIUM_FRANCE)
                    } else {
                        showSubscriptionDialog = true
                    }
                }
            }
        )
    }

    if (showSubscriptionDialog && currentUser != null) {
        SubscriptionDialog(
            userId = currentUser!!.id,
            onDismiss = { showSubscriptionDialog = false },
            onSubscribed = {
                showSubscriptionDialog = false
                scope.launch {
                    SupabaseManager.instance.refreshSubscription()
                    onSelectPlan(currentPaidNode?.id ?: SettingsRepository.PLAN_PREMIUM_FRANCE)
                }
            }
        )
    }

    if (showSupportWaysDialog) {
        SupportWaysDialog(
            showDonations = true,
            onDismiss = { showSupportWaysDialog = false }
        )
    }

    if (showTelegramWaysDialog) {
        SupportWaysDialog(
            showDonations = false,
            onDismiss = { showTelegramWaysDialog = false }
        )
    }
}
