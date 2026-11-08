package com.naua_security_mirage.app.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.components.MirageIcons
import com.naua_security_mirage.app.ui.theme.MirageColors
import kotlinx.coroutines.launch

@Composable
fun AuthDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = MirageColors.Ink,
        modifier = Modifier.width(390.dp),
        text = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(MirageColors.Card)
                    .border(1.2.dp, MirageColors.CardStroke, RoundedCornerShape(24.dp))
                    .padding(22.dp)
            ) {
                Column {
                    // Header Row matching dialog_auth.xml
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
                                tint = MirageColors.Amber,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Авторизация",
                                color = MirageColors.Ink,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Вход через аккаунт Google",
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

                    // Divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MirageColors.CardStroke)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Info Features / Benefits
                    Text(
                        text = "• Доступ к выделенным платным узлам на максимальной скорости\n" +
                                "• Синхронизация подписки на ПК и Android-устройствах\n" +
                                "• Персональные ключи доступа и защита конфиденциальности",
                        color = MirageColors.InkSoft,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )

                    if (statusText != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = statusText ?: "",
                            color = MirageColors.Amber,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Google Sign In Button (matching dialog_auth.xml)
                    OutlinedButton(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            statusText = "Открываем браузер для авторизации через Google..."
                            scope.launch {
                                val res = SupabaseManager.instance.signInWithGoogle()
                                isLoading = false
                                if (res.isSuccess) {
                                    statusText = "Успешный вход в аккаунт!"
                                    onSuccess()
                                } else {
                                    val err = res.exceptionOrNull()?.message ?: "Ошибка авторизации"
                                    errorMessage = err
                                    statusText = null
                                }
                            }
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = MirageColors.Card,
                            contentColor = MirageColors.Ink
                        ),
                        border = BorderStroke(1.5.dp, MirageColors.Amber),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = MirageColors.Amber,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MirageIcons.GoogleLogo(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Войти через Google",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MirageColors.Ink
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}
