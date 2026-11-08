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
import androidx.compose.material.icons.filled.ContentPaste
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

@Composable
fun AuthDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentUser by SupabaseManager.instance.currentUser.collectAsState()

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var showManualInput by remember { mutableStateOf(false) }
    var manualInputText by remember { mutableStateOf("") }
    var lastProcessedClipboard by remember { mutableStateOf<String?>(null) }

    // Auto-dismiss if logged in successfully
    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            onSuccess()
        }
    }

    // Auto-detect OAuth access token from clipboard while waiting
    LaunchedEffect(isLoading) {
        while (isLoading) {
            delay(1500)
            try {
                val cb = getClipboardText()?.trim()
                if (!cb.isNullOrBlank() && cb != lastProcessedClipboard &&
                    (cb.contains("access_token=") || cb.startsWith("mirage://") || (cb.startsWith("eyJ") && cb.length > 60))
                ) {
                    lastProcessedClipboard = cb
                    statusText = "Обнаружен токен в буфере обмена, авторизуемся..."
                    val res = SupabaseManager.instance.handleAuthCallbackUrl(cb)
                    if (res.isSuccess) {
                        isLoading = false
                        onSuccess()
                        break
                    } else {
                        errorMessage = res.exceptionOrNull()?.message ?: "Не удалось авторизоваться по ссылке из буфера"
                    }
                }
            } catch (_: Exception) {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = MirageColors.Ink,
        modifier = Modifier.width(410.dp),
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

                    // Info Features / Benefits matching Android dialog_auth.xml
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

                    Spacer(modifier = Modifier.height(18.dp))

                    // Google Sign In Button (matching dialog_auth.xml)
                    OutlinedButton(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            statusText = "Браузер открыт для входа через Google. Завершите вход в браузере."
                            scope.launch {
                                val res = SupabaseManager.instance.signInWithGoogle()
                                if (res.isFailure) {
                                    isLoading = false
                                    errorMessage = res.exceptionOrNull()?.message ?: "Ошибка запуска браузера"
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
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Ожидание авторизации...",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MirageColors.Ink
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

                    // Auxiliary helper actions for desktop OAuth
                    if (isLoading || statusText != null || showManualInput) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Button to paste address from browser / clipboard
                        OutlinedButton(
                            onClick = {
                                val cb = getClipboardText()?.trim()
                                if (cb.isNullOrBlank()) {
                                    errorMessage = "Буфер обмена пуст. Скопируйте адрес страницы из браузера (Ctrl+C)."
                                } else if (!cb.contains("access_token=") && !cb.startsWith("mirage://") && !cb.startsWith("eyJ")) {
                                    errorMessage = "В буфере нет токена авторизации. Скопируйте адрес страницы целиком (с #access_token=...)."
                                } else {
                                    errorMessage = null
                                    statusText = "Авторизация по ссылке из буфера..."
                                    scope.launch {
                                        val res = SupabaseManager.instance.handleAuthCallbackUrl(cb)
                                        if (res.isSuccess) {
                                            isLoading = false
                                            onSuccess()
                                        } else {
                                            errorMessage = res.exceptionOrNull()?.message ?: "Не удалось подтвердить вход"
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = Color(0xFF161821),
                                contentColor = MirageColors.Amber
                            ),
                            border = BorderStroke(1.dp, MirageColors.Amber.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = MirageColors.Amber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Вставить адрес из браузера",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MirageColors.Amber
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Toggle manual input
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showManualInput = !showManualInput }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (showManualInput) "Скрыть ручной ввод" else "Или ввести адрес страницы вручную",
                                color = MirageColors.InkSoft,
                                fontSize = 11.5.sp
                            )
                        }

                        if (showManualInput) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = manualInputText,
                                onValueChange = { manualInputText = it },
                                placeholder = {
                                    Text(
                                        text = "Вставьте https://...#access_token=... или токен",
                                        fontSize = 11.sp,
                                        color = MirageColors.InkMuted
                                    )
                                },
                                maxLines = 2,
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    textColor = MirageColors.Ink,
                                    focusedBorderColor = MirageColors.Amber,
                                    unfocusedBorderColor = MirageColors.CardStroke,
                                    backgroundColor = Color(0xFF161821)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    if (manualInputText.isBlank()) {
                                        errorMessage = "Поле ввода пусто"
                                    } else {
                                        errorMessage = null
                                        statusText = "Проверка введенного адреса..."
                                        scope.launch {
                                            val res = SupabaseManager.instance.handleAuthCallbackUrl(manualInputText.trim())
                                            if (res.isSuccess) {
                                                isLoading = false
                                                onSuccess()
                                            } else {
                                                errorMessage = res.exceptionOrNull()?.message ?: "Неверная ссылка или токен"
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = MirageColors.Amber,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                            ) {
                                Text(
                                    text = "Подтвердить вход",
                                    fontSize = 12.5.sp,
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

private fun getClipboardText(): String? {
    return try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            clipboard.getData(DataFlavor.stringFlavor) as? String
        } else null
    } catch (_: Exception) {
        null
    }
}
