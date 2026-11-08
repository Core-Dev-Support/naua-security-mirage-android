package com.naua_security_mirage.app.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors
import kotlinx.coroutines.launch

@Composable
fun AuthDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val colors = LocalMirageColors.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = colors.ink,
        modifier = Modifier.width(360.dp),
        text = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.card)
                    .border(1.2.dp, colors.cardStroke, RoundedCornerShape(24.dp))
                    .padding(22.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {

                    // Header Row
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
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = Color(0xFFE8A33D),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Вход в Mirage",
                                color = colors.ink,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Авторизация откроет доступ к платным серверам",
                                color = colors.inkSoft,
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
                                tint = colors.inkMuted,
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
                            .background(colors.cardStroke)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Info Features (Benefits)
                    Text(
                        text = "• Доступ к высокоскоростным платным серверам\n• Привязка подписки к единому аккаунту\n• Синхронизация между устройствами",
                        color = colors.inkSoft,
                        fontSize = 12.5.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFFF7A7A),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Google Sign In Button
                    OutlinedButton(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val res = SupabaseManager.instance.signInWithGoogle()
                                isLoading = false
                                if (res.isSuccess) {
                                    onSuccess()
                                    onDismiss()
                                } else {
                                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Ошибка авторизации через Google"
                                }
                            }
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = colors.card,
                            contentColor = colors.ink
                        ),
                        border = BorderStroke(1.5.dp, Color(0xFFE8A33D)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color(0xFFE8A33D),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "G",
                                    color = Color(0xFFE8A33D),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Продолжить с Google",
                                    color = colors.ink,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}
