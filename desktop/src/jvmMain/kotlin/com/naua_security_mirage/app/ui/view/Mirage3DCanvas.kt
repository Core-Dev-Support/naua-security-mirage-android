package com.naua_security_mirage.app.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors

@Composable
fun Mirage3DCanvas(
    style: String,
    vpnState: VpnState,
    onToggleConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMirageColors.current
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleConnect() })
            }
            .pointerInput(Unit) {
                detectDragGestures { _, _ -> }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(color = colors.accent.copy(alpha = 0.25f), radius = r)
            drawCircle(color = colors.accent, radius = r * 0.7f)
        }
        val isConn = vpnState == VpnState.CONNECTED
        Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = "Подключиться",
            tint = if (isConn) MirageColors.PowerIconConnected else Color.White,
            modifier = Modifier.fillMaxSize(0.4f)
        )
    }
}
