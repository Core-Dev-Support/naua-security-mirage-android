package com.naua_security_mirage.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * High-fidelity Vector Flags and Mirage Icons matching Android drawables 1-to-1.
 */
object MirageIcons {

    /**
     * Vector flag for France, Switzerland, Finland, Belarus.
     */
    @Composable
    fun Flag(flag: String, modifier: Modifier = Modifier.size(18.dp)) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(3.dp))
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                when (flag.lowercase()) {
                    "switzerland" -> drawSwitzerlandFlag()
                    "france" -> drawFranceFlag()
                    "finland" -> drawFinlandFlag()
                    "belarus" -> drawBelarusFlag()
                    else -> drawFranceFlag()
                }
                // Subtle border
                drawRect(
                    color = Color(0x33000000),
                    style = Stroke(width = 1.2f)
                )
            }
        }
    }

    private fun DrawScope.drawSwitzerlandFlag() {
        val w = size.width
        val h = size.height
        drawRect(color = Color(0xFFD52B1E))
        // White cross
        val barW = w * (2.4f / 18f)
        val barL = w * (10.8f / 18f)
        // Horizontal bar
        drawRect(
            color = Color.White,
            topLeft = Offset((w - barL) / 2f, (h - barW) / 2f),
            size = Size(barL, barW)
        )
        // Vertical bar
        drawRect(
            color = Color.White,
            topLeft = Offset((w - barW) / 2f, (h - barL) / 2f),
            size = Size(barW, barL)
        )
    }

    private fun DrawScope.drawFranceFlag() {
        val w = size.width
        val h = size.height
        val stripeW = w / 3f
        drawRect(color = Color(0xFF002395), topLeft = Offset(0f, 0f), size = Size(stripeW, h))
        drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(stripeW, 0f), size = Size(stripeW, h))
        drawRect(color = Color(0xFFED2939), topLeft = Offset(stripeW * 2f, 0f), size = Size(stripeW, h))
    }

    private fun DrawScope.drawFinlandFlag() {
        val w = size.width
        val h = size.height
        drawRect(color = Color(0xFFFFFFFF))
        val crossW = w * (3.2f / 18f)
        // Vertical cross bar
        val vOffset = w * (5.2f / 18f)
        drawRect(color = Color(0xFF002F6C), topLeft = Offset(vOffset, 0f), size = Size(crossW, h))
        // Horizontal cross bar
        val hOffset = h * (7.4f / 18f)
        drawRect(color = Color(0xFF002F6C), topLeft = Offset(0f, hOffset), size = Size(w, crossW))
    }

    private fun DrawScope.drawBelarusFlag() {
        val w = size.width
        val h = size.height
        val ornamentW = w * (4.9f / 18f)
        val redH = h * (11.6f / 16.8f)
        val greenH = h - redH

        // Red top stripe
        drawRect(color = Color(0xFFC8313E), topLeft = Offset(ornamentW, 0f), size = Size(w - ornamentW, redH))
        // Green bottom stripe
        drawRect(color = Color(0xFF1E7B45), topLeft = Offset(ornamentW, redH), size = Size(w - ornamentW, greenH))
        // Left ornament white bar
        drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(0f, 0f), size = Size(ornamentW, h))
        // Ornament red pattern representation
        drawRect(color = Color(0xFFC8313E).copy(alpha = 0.5f), topLeft = Offset(ornamentW * 0.25f, h * 0.15f), size = Size(ornamentW * 0.5f, h * 0.7f))
    }

    /**
     * Vector Telegram paper plane (ic_telegram)
     */
    @Composable
    fun TelegramPlane(modifier: Modifier = Modifier.size(18.dp), tint: Color = Color(0xFF2AABEE)) {
        Canvas(modifier = modifier) {
            val scaleX = size.width / 24f
            val scaleY = size.height / 24f
            val path = Path().apply {
                moveTo(21.9f * scaleX, 4.3f * scaleY)
                lineTo(18.7f * scaleX, 19.4f * scaleY)
                cubicTo(18.5f * scaleX, 20.4f * scaleY, 17.9f * scaleX, 20.7f * scaleY, 17.0f * scaleX, 20.2f * scaleY)
                lineTo(12.4f * scaleX, 16.8f * scaleY)
                lineTo(10.2f * scaleX, 18.9f * scaleY)
                cubicTo(10.0f * scaleX, 19.1f * scaleY, 9.8f * scaleX, 19.3f * scaleY, 9.3f * scaleX, 19.3f * scaleY)
                lineTo(9.6f * scaleX, 14.6f * scaleY)
                lineTo(17.9f * scaleX, 6.6f * scaleY)
                cubicTo(18.2f * scaleX, 6.3f * scaleY, 17.8f * scaleX, 6.2f * scaleY, 17.4f * scaleX, 6.5f * scaleY)
                lineTo(6.2f * scaleX, 12.7f * scaleY)
                lineTo(1.7f * scaleX, 11.1f * scaleY)
                cubicTo(0.7f * scaleX, 10.8f * scaleY, 0.7f * scaleX, 10.1f * scaleY, 1.9f * scaleX, 9.6f * scaleY)
                lineTo(20.9f * scaleX, 2.3f * scaleY)
                cubicTo(21.5f * scaleX, 1.9f * scaleY, 22.1f * scaleX, 2.2f * scaleY, 21.9f * scaleX, 4.3f * scaleY)
                close()

                moveTo(7.9f * scaleX, 14.1f * scaleY)
                lineTo(17.8f * scaleX, 7.9f * scaleY)
                cubicTo(18.3f * scaleX, 7.6f * scaleY, 18.7f * scaleX, 7.8f * scaleY, 18.4f * scaleX, 8.1f * scaleY)
                lineTo(10.0f * scaleX, 15.7f * scaleY)
                lineTo(9.7f * scaleX, 19.0f * scaleY)
                lineTo(7.9f * scaleX, 14.1f * scaleY)
                close()
            }
            drawPath(path, color = tint)
        }
    }

    /**
     * Vector Heart (ic_heart)
     */
    @Composable
    fun Heart(modifier: Modifier = Modifier.size(16.dp), tint: Color = Color(0xFFEF4444)) {
        Canvas(modifier = modifier) {
            val sx = size.width / 24f
            val sy = size.height / 24f
            val path = Path().apply {
                moveTo(12f * sx, 21.35f * sy)
                lineTo(10.55f * sx, 20.03f * sy)
                cubicTo(5.4f * sx, 15.36f * sy, 2f * sx, 12.28f * sy, 2f * sx, 8.5f * sy)
                cubicTo(2f * sx, 5.42f * sy, 4.42f * sx, 3f * sy, 7.5f * sx, 3f * sy)
                cubicTo(9.24f * sx, 3f * sy, 10.91f * sx, 3.81f * sy, 12f * sx, 5.09f * sy)
                cubicTo(13.09f * sx, 3.81f * sy, 14.76f * sx, 3f * sy, 16.5f * sx, 3f * sy)
                cubicTo(19.58f * sx, 3f * sy, 22f * sx, 5.42f * sy, 22f * sx, 8.5f * sy)
                cubicTo(22f * sx, 12.28f * sy, 18.6f * sx, 15.36f * sy, 13.45f * sx, 20.04f * sy)
                lineTo(12f * sx, 21.35f * sy)
                close()
            }
            drawPath(path, color = tint)
        }
    }

    /**
     * Vector Google Logo (ic_google)
     */
    @Composable
    fun GoogleLogo(modifier: Modifier = Modifier.size(20.dp)) {
        Canvas(modifier = modifier) {
            val sx = size.width / 24f
            val sy = size.height / 24f

            // Blue
            val pathBlue = Path().apply {
                moveTo(23.745f * sx, 12.27f * sy)
                cubicTo(23.745f * sx, 11.56f * sy, 23.685f * sx, 10.87f * sy, 23.555f * sx, 10.2f * sy)
                lineTo(12f * sx, 10.2f * sy)
                lineTo(12f * sx, 14.71f * sy)
                lineTo(18.6f * sx, 14.71f * sy)
                cubicTo(18.31f * sx, 16.23f * sy, 17.46f * sx, 17.53f * sy, 16.2f * sx, 18.39f * sy)
                lineTo(16.2f * sx, 21.44f * sy)
                lineTo(20.08f * sx, 21.44f * sy)
                cubicTo(22.35f * sx, 19.35f * sy, 23.745f * sx, 16.27f * sy, 23.745f * sx, 12.27f * sy)
                close()
            }
            drawPath(pathBlue, color = Color(0xFF4285F4))

            // Green
            val pathGreen = Path().apply {
                moveTo(12f * sx, 24f * sy)
                cubicTo(15.24f * sx, 24f * sy, 17.95f * sx, 22.92f * sy, 19.93f * sx, 21.09f * sy)
                lineTo(16.05f * sx, 18.04f * sy)
                cubicTo(14.97f * sx, 18.76f * sy, 13.6f * sx, 19.2f * sy, 12f * sx, 19.2f * sy)
                cubicTo(8.88f * sx, 19.2f * sy, 6.23f * sx, 17.1f * sy, 5.28f * sx, 14.27f * sy)
                lineTo(1.25f * sx, 14.27f * sy)
                lineTo(1.25f * sx, 17.4f * sy)
                cubicTo(3.26f * sx, 21.36f * sy, 7.33f * sx, 24f * sy, 12f * sx, 24f * sy)
                close()
            }
            drawPath(pathGreen, color = Color(0xFF34A853))

            // Yellow
            val pathYellow = Path().apply {
                moveTo(5.28f * sx, 14.27f * sy)
                cubicTo(5.03f * sx, 13.55f * sy, 4.9f * sx, 12.78f * sy, 4.9f * sx, 12f * sy)
                cubicTo(4.9f * sx, 11.22f * sy, 5.04f * sx, 10.45f * sy, 5.28f * sx, 9.73f * sy)
                lineTo(5.28f * sx, 6.6f * sy)
                lineTo(1.25f * sx, 6.6f * sy)
                cubicTo(0.45f * sx, 8.18f * sy, 0f, 9.99f * sy, 0f, 12f * sy)
                cubicTo(0f, 13.82f * sy, 0.45f * sx, 15.63f * sy, 1.25f * sx, 17.4f * sy)
                lineTo(5.28f * sx, 14.27f * sy)
                close()
            }
            drawPath(pathYellow, color = Color(0xFFFBBC05))

            // Red
            val pathRed = Path().apply {
                moveTo(12f * sx, 4.75f * sy)
                cubicTo(13.77f * sx, 4.75f * sy, 15.35f * sx, 5.36f * sy, 16.6f * sx, 6.55f * sy)
                lineTo(20.02f * sx, 3.13f * sy)
                cubicTo(17.95f * sx, 1.19f * sy, 15.24f * sx, 0f, 12f * sx, 0f)
                cubicTo(7.33f * sx, 0f, 3.26f * sx, 2.64f * sy, 1.25f * sx, 6.6f * sy)
                lineTo(5.28f * sx, 9.73f * sy)
                cubicTo(6.23f * sx, 6.9f * sy, 8.88f * sx, 4.75f * sy, 12f * sx, 4.75f * sy)
                close()
            }
            drawPath(pathRed, color = Color(0xFFEA4335))
        }
    }

    /**
     * Vector Power Icon matching ic_power.xml
     */
    @Composable
    fun PowerIcon(modifier: Modifier = Modifier.size(44.dp), tint: Color = Color(0xFF2A1706)) {
        Canvas(modifier = modifier) {
            val sx = size.width / 24f
            val sy = size.height / 24f
            // Vertical bar
            drawLine(
                color = tint,
                start = Offset(12f * sx, 3f * sy),
                end = Offset(12f * sx, 11f * sy),
                strokeWidth = 2.4f * sx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // Arc
            drawArc(
                color = tint,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(4f * sx, 4f * sy),
                size = Size(16f * sx, 16f * sy),
                style = Stroke(width = 2.4f * sx, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
        }
    }
}
