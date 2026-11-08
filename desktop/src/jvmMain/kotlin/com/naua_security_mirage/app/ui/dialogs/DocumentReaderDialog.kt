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
import com.naua_security_mirage.app.ui.theme.MirageColors
import java.io.File

@Composable
fun DocumentReaderDialog(
    title: String,
    fileName: String,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    val content = try {
        // 1. Try classpath resource
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("legal/$fileName")
            ?: Thread.currentThread().contextClassLoader.getResourceAsStream(fileName)
        if (stream != null) {
            stream.bufferedReader().use { it.readText() }
        } else {
            // 2. Try file system
            val fileInDocs = File("docs/legal/$fileName")
            if (fileInDocs.exists()) {
                fileInDocs.readText()
            } else {
                val rootDoc = File(fileName)
                if (rootDoc.exists()) rootDoc.readText() else "Документ загружается..."
            }
        }
    } catch (_: Exception) {
        "Не удалось загрузить документ"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        buttons = {},
        backgroundColor = Color.Transparent,
        contentColor = MirageColors.Ink,
        modifier = Modifier.width(520.dp).height(580.dp),
        text = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MirageColors.Card)
                    .border(1.2.dp, MirageColors.CardStroke, RoundedCornerShape(24.dp))
                    .padding(22.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = MirageColors.Ink,
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
                                tint = MirageColors.InkMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MirageColors.CardStroke)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MirageColors.Bg)
                            .border(1.dp, MirageColors.CardStroke, RoundedCornerShape(14.dp))
                            .padding(16.dp)
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = content,
                            color = MirageColors.InkSoft,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MirageColors.Amber,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Text(
                            text = "Понятно",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    )
}
