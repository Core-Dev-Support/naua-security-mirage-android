package com.naua_security_mirage.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import java.io.File

@Composable
fun DocumentReaderDialog(
    title: String,
    fileName: String,
    onDismiss: () -> Unit
) {
    val colors = LocalMirageColors.current
    val scrollState = rememberScrollState()

    val content = try {
        val fileInDocs = File("docs/legal/$fileName")
        if (fileInDocs.exists()) {
            fileInDocs.readText()
        } else {
            val rootDoc = File(fileName)
            if (rootDoc.exists()) rootDoc.readText() else "Документ загружается..."
        }
    } catch (_: Exception) {
        "Не удалось загрузить документ"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = colors.ink,
        modifier = Modifier.width(520.dp).height(560.dp),
        text = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.card)
                    .border(1.2.dp, colors.cardStroke, RoundedCornerShape(24.dp))
                    .padding(22.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = colors.ink,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
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

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.bg)
                            .border(1.dp, colors.cardStroke, RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Column(modifier = Modifier.verticalScroll(scrollState)) {
                            Text(
                                text = content,
                                color = colors.inkSoft,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFFE8A33D),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Text("Понятно", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    )
}
