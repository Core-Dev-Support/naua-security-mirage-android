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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.model.VlessServer
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors
import com.naua_security_mirage.app.ui.view.Mirage3DCanvas
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.dialogs.AuthDialog
import com.naua_security_mirage.app.ui.dialogs.SubscriptionDialog
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

enum class ServerPlan {
    FREE,
    PREMIUM_FRANCE
}

@Composable
fun MainScreen(
    vpnState: VpnState,
    currentServer: VlessServer?,
    downloadSpeed: String,
    uploadSpeed: String,
    settingsRepository: SettingsRepository,
    selectedPlan: ServerPlan = ServerPlan.FREE,
    onSelectPlan: (ServerPlan) -> Unit = {},
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
    val subscription by SupabaseManager.instance.subscription.collectAsState()

    var showUpdateDialog by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }

    val isConnected = vpnState == VpnState.CONNECTED
    val isConnecting = vpnState == VpnState.CONNECTING
    val isDisconnecting = vpnState == VpnState.DISCONNECTING

    // Glow ring pulse animation during CONNECTING / CONNECTED
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        // Top Header Row matching Android headerBrand (Mirage / NAUA Security + 3 buttons)
        MirageHeader(
            title = "Mirage",
            subtitle = "NAUA Security",
            isSubScreen = false,
            isRefreshing = isRefreshing,
            hasUpdate = hasUpdate,
            onUpdateClick = { showUpdateDialog = true },
            onRefreshClick = onRefreshServers,
            onSettingsClick = onOpenSettings
        )

        // Main Center Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(36.dp))

            // Connect Button Area (Glow Ring 214dp + Connect Button 180dp)
            Box(
                modifier = Modifier.size(214.dp),
                contentAlignment = Alignment.Center
            ) {
                // Glow Ring behind button (214dp)
                if (isConnected || isConnecting) {
                    Box(
                        modifier = Modifier
                            .size(214.dp)
                            .scale(glowScale)
                            .clip(CircleShape)
                            .background(colors.accent.copy(alpha = 0.15f * glowAlpha))
                            .border(2.dp, colors.accent.copy(alpha = 0.45f * glowAlpha), CircleShape)
                    )
                }

                // Circular Connect Button (180dp)
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
                                            colors.accent,
                                            MirageColors.AmberDeep
                                        ),
                                        radius = 200f
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
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Подключиться",
                            tint = if (isConnected) MirageColors.PowerIconConnected else Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    } else {
                        // 3D Canvas Button (Earth / Quantum / Holo / Realistic)
                        Mirage3DCanvas(
                            style = settingsRepository.connectBtnStyle,
                            vpnState = vpnState,
                            onToggleConnect = onToggleConnect,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Status Text (18sp bold ink)
            val statusText = when (vpnState) {
                VpnState.DISCONNECTED -> "Отключено"
                VpnState.CONNECTING -> "Подключение..."
                VpnState.CONNECTED -> "Подключено"
                VpnState.DISCONNECTING -> "Отключение..."
            }
            Text(
                text = statusText,
                color = colors.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp)
            )

            // Status Subtitle (13.5sp inkSoft)
            val statusSubText = when (vpnState) {
                VpnState.DISCONNECTED -> "Нажми, чтобы включить защиту"
                VpnState.CONNECTING -> "Поиск наилучшего сервера..."
                VpnState.CONNECTED -> {
                    val minutes = sessionSeconds / 60
                    val remSeconds = sessionSeconds % 60
                    val timeStr = String.format("%02d:%02d", minutes, remSeconds)
                    val pingDisplay = currentServer?.pingMs?.takeIf { it in 1..9998 } ?: 45L
                    "Время сессии $timeStr • $pingDisplay ms"
                }
                VpnState.DISCONNECTING -> "Отключение..."
            }
            Text(
                text = statusSubText,
                color = colors.inkSoft,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 5.dp)
            )

            // Server Selector: Бесплатный vs Платный Франция
            Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.card)
                    .border(1.dp, colors.cardStroke, RoundedCornerShape(14.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Free Server
                val isFree = selectedPlan == ServerPlan.FREE
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isFree) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
                        .border(
                            width = if (isFree) 1.dp else 0.dp,
                            color = if (isFree) colors.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectPlan(ServerPlan.FREE) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "🌐", fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Бесплатный",
                            color = if (isFree) colors.accent else colors.inkSoft,
                            fontSize = 12.5.sp,
                            fontWeight = if (isFree) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }

                // Premium France Server
                val isFrance = selectedPlan == ServerPlan.PREMIUM_FRANCE
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isFrance) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
                        .border(
                            width = if (isFrance) 1.dp else 0.dp,
                            color = if (isFrance) colors.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            if (currentUser == null) {
                                showAuthDialog = true
                            } else if (!SupabaseManager.instance.hasActiveSubscription()) {
                                showSubscriptionDialog = true
                            } else {
                                onSelectPlan(ServerPlan.PREMIUM_FRANCE)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "🇫🇷", fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Платный Франция",
                            color = if (isFrance) colors.accent else colors.inkSoft,
                            fontSize = 12.5.sp,
                            fontWeight = if (isFrance) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            // Live Speed Chip (Visible only when CONNECTED)
            AnimatedVisibility(
                visible = isConnected,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.card)
                        .border(1.5.dp, colors.cardStroke, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = colors.inkMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = downloadSpeed,
                        color = colors.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(10.dp)
                            .background(colors.cardStroke)
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = colors.inkMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = uploadSpeed,
                        color = colors.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Support Project Button (Pill 46dp, heart icon, bold 13.5sp text)
            Row(
                modifier = Modifier
                    .padding(top = if (isConnected) 20.dp else 36.dp)
                    .height(46.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(colors.card)
                    .border(1.dp, colors.cardStroke, RoundedCornerShape(30.dp))
                    .clickable {
                        try {
                            Desktop.getDesktop().browse(URI("https://pay.cloudtips.ru/p/3bbf2b6b"))
                        } catch (_: Exception) {}
                    }
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Поддержать проект",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Поддержать проект",
                    color = colors.ink,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Hint Card at Bottom matching Android hintCard
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.card)
                    .border(1.dp, colors.cardStroke, RoundedCornerShape(18.dp))
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
                        append("Mirage сам выбирает сервер.\n\n")
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = colors.ink)) {
                            append("Выбор локации не нужен.")
                        }
                        append("\nПросто нажми кнопку подключения.")
                    },
                    color = colors.inkSoft,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }

    // Update / Version Dialog
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = {
                Text(
                    text = "NAUA Security Mirage PC",
                    color = colors.ink,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (hasUpdate) {
                        "Доступно обновление для Windows! Нажмите «Скачать», чтобы перейти к загрузке новой версии."
                    } else {
                        "У вас установлена актуальная версия v1.3.0.\nВсе сервера и модули защиты обновлены."
                    },
                    color = colors.inkSoft,
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
                        color = colors.accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            backgroundColor = colors.card,
            contentColor = colors.ink
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
                        onSelectPlan(ServerPlan.PREMIUM_FRANCE)
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
                    onSelectPlan(ServerPlan.PREMIUM_FRANCE)
                }
            }
        )
    }
}
