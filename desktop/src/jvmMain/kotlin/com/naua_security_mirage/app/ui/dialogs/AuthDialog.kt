package com.naua_security_mirage.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.supabase.SupabaseManager
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import kotlinx.coroutines.launch

@Composable
fun AuthDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val colors = LocalMirageColors.current
    val scope = rememberCoroutineScope()

    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
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
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.card)
                    .border(1.dp, colors.cardStroke, RoundedCornerShape(20.dp))
                    .padding(22.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Header with title and close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSignUp) "Регистрация" else "Вход в аккаунт",
                            color = colors.ink,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
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

                    // Tab Selector: Вход / Регистрация
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.bg)
                            .padding(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isSignUp) colors.card else Color.Transparent)
                                .clickable {
                                    isSignUp = false
                                    errorMessage = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Вход",
                                color = if (!isSignUp) colors.accent else colors.inkSoft,
                                fontSize = 13.sp,
                                fontWeight = if (!isSignUp) FontWeight.Bold else FontWeight.Normal
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSignUp) colors.card else Color.Transparent)
                                .clickable {
                                    isSignUp = true
                                    errorMessage = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Регистрация",
                                color = if (isSignUp) colors.accent else colors.inkSoft,
                                fontSize = 13.sp,
                                fontWeight = if (isSignUp) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Email Field
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; errorMessage = null },
                        label = { Text("Электронная почта", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = null,
                                tint = colors.inkMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = colors.ink,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.cardStroke,
                            focusedLabelColor = colors.accent,
                            unfocusedLabelColor = colors.inkMuted,
                            cursorColor = colors.accent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Password Field
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        label = { Text("Пароль", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = colors.inkMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = colors.inkMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = colors.ink,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.cardStroke,
                            focusedLabelColor = colors.accent,
                            unfocusedLabelColor = colors.inkMuted,
                            cursorColor = colors.accent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Error Message
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Submit Button (Email)
                    Button(
                        onClick = {
                            if (email.isBlank() || password.isBlank()) {
                                errorMessage = "Заполните все поля"
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val result = if (isSignUp) {
                                    SupabaseManager.instance.signUpWithEmail(email, password)
                                } else {
                                    SupabaseManager.instance.signInWithEmail(email, password)
                                }
                                isLoading = false
                                if (result.isSuccess) {
                                    onSuccess()
                                    onDismiss()
                                } else {
                                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Ошибка авторизации"
                                }
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = colors.accent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Text(
                                text = if (isSignUp) "Зарегистрироваться" else "Войти",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Divider "или"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f).height(1.dp).background(colors.cardStroke))
                        Text(
                            text = "или",
                            color = colors.inkMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                        Box(modifier = Modifier.weight(1f).height(1.dp).background(colors.cardStroke))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Google OAuth Button
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
                                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Ошибка входа через Google"
                                }
                            }
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = colors.card,
                            contentColor = colors.ink
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardStroke),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Text(
                            text = "🌐 Войти через Google",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                    }
                }
            }
        }
    )
}
