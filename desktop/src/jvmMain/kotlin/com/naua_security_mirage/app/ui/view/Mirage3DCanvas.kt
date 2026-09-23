package com.naua_security_mirage.app.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.naua_security_mirage.app.data.model.VpnState
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import com.naua_security_mirage.app.ui.theme.MirageColors
import org.jetbrains.skia.*
import kotlin.math.*

@Composable
fun Mirage3DCanvas(
    style: String,
    vpnState: VpnState,
    onToggleConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMirageColors.current
    var rotY by remember { mutableStateOf(0f) }
    var rotX by remember { mutableStateOf(0.15f) }
    var frameTime by remember { mutableStateOf(0L) }
    var energy by remember { mutableStateOf(0.3f) }
    var spinSpeed by remember { mutableStateOf(0.006f) }

    // 60 FPS continuous animation loop
    LaunchedEffect(vpnState, style) {
        var last = -1L
        while (true) {
            withFrameMillis { now ->
                val dt = if (last < 0L) 0.016f else ((now - last).toFloat() / 1000f).coerceIn(0.001f, 0.05f)
                last = now

                val (targetSpeed, targetEnergy) = when (vpnState) {
                    VpnState.DISCONNECTED -> 0.005f to 0.28f
                    VpnState.CONNECTING -> {
                        val pulse = (sin(now / 150.0) * 0.5 + 0.5).toFloat()
                        0.065f to (0.5f + 0.5f * pulse)
                    }
                    VpnState.CONNECTED -> 0.016f to 1.0f
                    VpnState.DISCONNECTING -> 0.040f to 0.40f
                }

                spinSpeed += (targetSpeed - spinSpeed) * (dt * 4f).coerceIn(0f, 1f)
                energy = (energy + (targetEnergy - energy) * (dt * 5f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                rotY += spinSpeed
                frameTime = now
            }
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        onToggleConnect()
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    rotY += dragAmount.x * 0.008f
                    rotX = (rotX + dragAmount.y * 0.004f).coerceIn(-0.6f, 0.6f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvas = drawContext.canvas.nativeCanvas
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val r = min(w, h) * 0.40f

            if (r <= 0f) return@Canvas

            when (style) {
                SettingsRepository.STYLE_3D_CYBER_EARTH -> {
                    drawCyberEarth(canvas, cx, cy, r, rotY, rotX, energy, colors.accent, frameTime)
                }
                SettingsRepository.STYLE_3D_QUANTUM_CORE -> {
                    drawQuantumCore(canvas, cx, cy, r, rotY, rotX, energy, colors.accent, frameTime)
                }
                SettingsRepository.STYLE_3D_HOLO_SHIELD -> {
                    drawHoloShield(canvas, cx, cy, r, rotY, rotX, energy, colors.accent, frameTime)
                }
                SettingsRepository.STYLE_3D_REALISTIC_EARTH -> {
                    drawRealisticEarth(canvas, cx, cy, r, rotY, rotX, energy, colors.accent, frameTime)
                }
                else -> {
                    // Standard Style (Big glowing circle with pulse)
                    drawStandardButton(canvas, cx, cy, r, vpnState, colors.accent, energy)
                }
            }
        }

        // Center icon for standard or overlay
        if (style == SettingsRepository.STYLE_STANDARD) {
            val isConn = vpnState == VpnState.CONNECTED
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = "Подключиться",
                tint = if (isConn) MirageColors.PowerIconConnected else colors.accent,
                modifier = Modifier.size(44.dp)
            )
        }
    }
}

// -------------------------------------------------------------
// 3D Math Projection Utilities
// -------------------------------------------------------------
private fun project3D(
    rRing: Float,
    yPlane: Float,
    lonDeg: Float,
    rotY: Float,
    rotX: Float,
    tiltZ: Float
): FloatArray {
    val lonRad = Math.toRadians(lonDeg.toDouble()).toFloat() + rotY
    val x0 = rRing * sin(lonRad)
    val z0 = rRing * cos(lonRad)

    val cosX = cos(rotX)
    val sinX = sin(rotX)
    val y1 = yPlane * cosX - z0 * sinX
    val z1 = yPlane * sinX + z0 * cosX

    val cosZ = cos(tiltZ)
    val sinZ = sin(tiltZ)
    val x2 = x0 * cosZ - y1 * sinZ
    val y2 = x0 * sinZ + y1 * cosZ

    return floatArrayOf(x2, y2, z1)
}

// -------------------------------------------------------------
// Cyber Earth Rendering Engine
// -------------------------------------------------------------
private fun drawCyberEarth(
    canvas: org.jetbrains.skia.Canvas,
    cx: Float,
    cy: Float,
    r: Float,
    rotY: Float,
    rotX: Float,
    energy: Float,
    accentColor: Color,
    frameTime: Long
) {
    // 1. Atmosphere back glow
    val glowPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = 5f
        color = accentColor.copy(alpha = (0.25f * energy).coerceIn(0f, 1f)).toArgb()
        imageFilter = ImageFilter.makeBlur(16f, 16f, FilterTileMode.DECAL)
    }
    canvas.drawCircle(cx, cy, r + 4f, glowPaint)

    // 2. Globe Dark Void Body
    val globeBgPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        shader = Shader.makeRadialGradient(
            cx - r * 0.35f, cy - r * 0.35f, r * 1.35f,
            intArrayOf(
                accentColor.copy(alpha = 0.18f).toArgb(),
                Color(0xFF0C1628).toArgb(),
                Color(0xFF030611).toArgb()
            ),
            floatArrayOf(0f, 0.6f, 1f)
        )
    }
    canvas.drawCircle(cx, cy, r, globeBgPaint)

    // 3. Grid Parallels (Latitudes)
    val gridPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = 1.2f
        color = accentColor.copy(alpha = (0.22f * energy).coerceIn(0f, 1f)).toArgb()
    }
    val lats = floatArrayOf(-60f, -30f, 0f, 30f, 60f)
    for (lat in lats) {
        val latRad = Math.toRadians(lat.toDouble()).toFloat()
        val yPlane = -r * sin(latRad)
        val rRing = r * cos(latRad)
        val path = Path()
        var first = true
        for (deg in 0..360 step 8) {
            val pt = project3D(rRing, yPlane, deg.toFloat(), rotY, rotX, -0.12f)
            if (pt[2] > 0f) {
                if (first) {
                    path.moveTo(cx + pt[0], cy + pt[1])
                    first = false
                } else {
                    path.lineTo(cx + pt[0], cy + pt[1])
                }
            } else {
                first = true
            }
        }
        canvas.drawPath(path, gridPaint)
    }

    // 4. Grid Meridians (Longitudes)
    for (lon in 0 until 360 step 30) {
        val path = Path()
        var first = true
        for (lat in -85..85 step 6) {
            val latRad = Math.toRadians(lat.toDouble()).toFloat()
            val yPlane = -r * sin(latRad)
            val rRing = r * cos(latRad)
            val pt = project3D(rRing, yPlane, lon.toFloat(), rotY, rotX, -0.12f)
            if (pt[2] > 0f) {
                if (first) {
                    path.moveTo(cx + pt[0], cy + pt[1])
                    first = false
                } else {
                    path.lineTo(cx + pt[0], cy + pt[1])
                }
            } else {
                first = true
            }
        }
        canvas.drawPath(path, gridPaint)
    }

    // 5. Continents outline in neon amber/accent
    val landPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = 1.8f
        color = accentColor.copy(alpha = (0.85f * energy).coerceIn(0f, 1f)).toArgb()
    }
    val landFillPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        color = accentColor.copy(alpha = (0.12f * energy).coerceIn(0f, 1f)).toArgb()
    }

    for (land in CONTINENTS) {
        val path = Path()
        var first = true
        var visible = false
        var i = 0
        while (i < land.size) {
            val lat = land[i]
            val lon = land[i + 1]
            val latRad = Math.toRadians(lat.toDouble()).toFloat()
            val yPlane = -r * sin(latRad)
            val rRing = r * cos(latRad)
            val pt = project3D(rRing, yPlane, lon, rotY, rotX, -0.12f)
            if (pt[2] > 0f) {
                visible = true
                if (first) {
                    path.moveTo(cx + pt[0], cy + pt[1])
                    first = false
                } else {
                    path.lineTo(cx + pt[0], cy + pt[1])
                }
            } else {
                first = true
            }
            i += 2
        }
        if (visible) {
            canvas.drawPath(path, landFillPaint)
            canvas.drawPath(path, landPaint)
        }
    }

    // 6. Neon Outer Boundary Ring
    val rimPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = 2.5f
        color = accentColor.copy(alpha = 0.9f).toArgb()
    }
    canvas.drawCircle(cx, cy, r, rimPaint)
}

// -------------------------------------------------------------
// Quantum Core Engine
// -------------------------------------------------------------
private fun drawQuantumCore(
    canvas: org.jetbrains.skia.Canvas,
    cx: Float,
    cy: Float,
    r: Float,
    rotY: Float,
    rotX: Float,
    energy: Float,
    accentColor: Color,
    frameTime: Long
) {
    // Energy core central sphere
    val coreR = r * 0.42f
    val corePaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        shader = Shader.makeRadialGradient(
            cx, cy, coreR * 1.2f,
            intArrayOf(
                Color.White.toArgb(),
                accentColor.toArgb(),
                accentColor.copy(alpha = 0.1f).toArgb()
            ),
            floatArrayOf(0f, 0.5f, 1f)
        )
    }
    canvas.drawCircle(cx, cy, coreR, corePaint)

    // Orbiting Gimbal Rings
    val ringR = r * 0.92f
    val ringPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = 2.2f
        color = accentColor.copy(alpha = (0.85f * energy).coerceIn(0f, 1f)).toArgb()
    }

    val angles = floatArrayOf(rotY, rotY * 1.4f + 1.2f, -rotY * 0.9f + 2.4f)
    val tilts = floatArrayOf(rotX, -rotX * 1.5f + 0.5f, rotX * 0.8f - 0.7f)

    for (k in 0..2) {
        val path = Path()
        var first = true
        val ry = angles[k]
        val rx = tilts[k]
        for (deg in 0..360 step 6) {
            val rad = Math.toRadians(deg.toDouble()).toFloat()
            val pt = project3D(ringR, 0f, deg.toFloat(), ry, rx, k * 0.4f)
            if (pt[2] > -ringR * 0.5f) {
                if (first) {
                    path.moveTo(cx + pt[0], cy + pt[1])
                    first = false
                } else {
                    path.lineTo(cx + pt[0], cy + pt[1])
                }
            } else {
                first = true
            }
        }
        canvas.drawPath(path, ringPaint)
    }

    // Outer shield rim
    val rimPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = 2f
        color = accentColor.copy(alpha = (0.4f * energy).coerceIn(0f, 1f)).toArgb()
    }
    canvas.drawCircle(cx, cy, r, rimPaint)
}

// -------------------------------------------------------------
// Holo-Shield Engine
// -------------------------------------------------------------
private fun drawHoloShield(
    canvas: org.jetbrains.skia.Canvas,
    cx: Float,
    cy: Float,
    r: Float,
    rotY: Float,
    rotX: Float,
    energy: Float,
    accentColor: Color,
    frameTime: Long
) {
    // Holographic background sphere
    val bgPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        shader = Shader.makeRadialGradient(
            cx - r * 0.2f, cy - r * 0.2f, r * 1.2f,
            intArrayOf(
                accentColor.copy(alpha = (0.25f * energy).coerceIn(0f, 1f)).toArgb(),
                Color(0xFF0D1B2A).toArgb(),
                Color(0xFF030712).toArgb()
            ),
            floatArrayOf(0f, 0.65f, 1f)
        )
    }
    canvas.drawCircle(cx, cy, r, bgPaint)

    // Geodesic Hexagonal Facets
    val facetPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = 1.4f
        color = accentColor.copy(alpha = (0.7f * energy).coerceIn(0f, 1f)).toArgb()
    }
    val facetFill = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        color = accentColor.copy(alpha = (0.08f * energy).coerceIn(0f, 1f)).toArgb()
    }

    for (lat in -60..60 step 30) {
        for (lon in 0 until 360 step 45) {
            val latRad = Math.toRadians(lat.toDouble()).toFloat()
            val yPlane = -r * sin(latRad)
            val rRing = r * cos(latRad)
            val pt = project3D(rRing, yPlane, lon.toFloat(), rotY, rotX, 0f)
            if (pt[2] > 0.1f) {
                val hexR = 18f * (pt[2] / r).coerceIn(0.5f, 1.2f)
                val path = Path()
                for (a in 0 until 6) {
                    val angle = a * PI / 3.0
                    val hx = cx + pt[0] + (hexR * cos(angle)).toFloat()
                    val hy = cy + pt[1] + (hexR * sin(angle)).toFloat()
                    if (a == 0) path.moveTo(hx, hy) else path.lineTo(hx, hy)
                }
                path.close()
                canvas.drawPath(path, facetFill)
                canvas.drawPath(path, facetPaint)
            }
        }
    }

    // Outer Boundary
    val rimPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = 2.5f
        color = accentColor.copy(alpha = 0.9f).toArgb()
    }
    canvas.drawCircle(cx, cy, r, rimPaint)
}

// -------------------------------------------------------------
// Realistic Earth Engine
// -------------------------------------------------------------
private fun drawRealisticEarth(
    canvas: org.jetbrains.skia.Canvas,
    cx: Float,
    cy: Float,
    r: Float,
    rotY: Float,
    rotX: Float,
    energy: Float,
    accentColor: Color,
    frameTime: Long
) {
    // 1. Deep blue ocean body
    val oceanPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        shader = Shader.makeRadialGradient(
            cx - r * 0.35f, cy - r * 0.35f, r * 1.45f,
            intArrayOf(
                Color(0xFF165BAA).toArgb(), // Atlantic bright blue
                Color(0xFF0A2B68).toArgb(), // Deep abyss
                Color(0xFF030C24).toArgb()  // Midnight abyssal navy
            ),
            floatArrayOf(0f, 0.55f, 1f)
        )
    }
    canvas.drawCircle(cx, cy, r, oceanPaint)

    // 2. Continents with realistic biomes (Green vegetation, golden Sahara/Arabia, white Greenland)
    val vegPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        color = Color(0xFF2E6F40).toArgb() // Forest green
    }
    val desertPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        color = Color(0xFFB0894C).toArgb() // Desert gold
    }
    val icePaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        color = Color(0xFFD4E6F1).toArgb() // Ice white
    }

    for (land in CONTINENTS) {
        val path = Path()
        var first = true
        var visible = false
        var i = 0
        while (i < land.size) {
            val lat = land[i]
            val lon = land[i + 1]
            val latRad = Math.toRadians(lat.toDouble()).toFloat()
            val yPlane = -r * sin(latRad)
            val rRing = r * cos(latRad)
            val pt = project3D(rRing, yPlane, lon, rotY, rotX, -0.12f)
            if (pt[2] > 0f) {
                visible = true
                if (first) {
                    path.moveTo(cx + pt[0], cy + pt[1])
                    first = false
                } else {
                    path.lineTo(cx + pt[0], cy + pt[1])
                }
            } else {
                first = true
            }
            i += 2
        }
        if (visible) {
            canvas.drawPath(path, vegPaint)
        }
    }

    // 3. Night shadow gradient (Day/Night terminator)
    val shadowPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        shader = Shader.makeLinearGradient(
            cx - r * 0.6f, cy - r * 0.6f,
            cx + r * 0.9f, cy + r * 0.9f,
            intArrayOf(
                Color.Transparent.toArgb(),
                Color.Transparent.toArgb(),
                Color(0x70FF6E1E).toArgb(), // Sunset orange twilight
                Color(0xCC040C1C).toArgb(), // Deep shadow
                Color(0xF001040D).toArgb()  // Deep space night
            ),
            floatArrayOf(0f, 0.45f, 0.56f, 0.72f, 1f)
        )
    }
    canvas.drawCircle(cx, cy, r, shadowPaint)

    // 4. Outer atmospheric ozone glow
    val rimPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = 2.5f
        color = Color(0xFF38BDF8).copy(alpha = 0.8f).toArgb()
    }
    canvas.drawCircle(cx, cy, r, rimPaint)
}

// -------------------------------------------------------------
// Standard Button Engine
// -------------------------------------------------------------
private fun drawStandardButton(
    canvas: org.jetbrains.skia.Canvas,
    cx: Float,
    cy: Float,
    r: Float,
    vpnState: VpnState,
    accentColor: Color,
    energy: Float
) {
    val isConnected = vpnState == VpnState.CONNECTED
    val isConnecting = vpnState == VpnState.CONNECTING

    // Glowing outer ring
    val ringPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = 6f
        color = if (isConnected) {
            accentColor.copy(alpha = 0.9f).toArgb()
        } else if (isConnecting) {
            accentColor.copy(alpha = (0.5f + 0.4f * energy).coerceIn(0f, 1f)).toArgb()
        } else {
            accentColor.copy(alpha = 0.3f).toArgb()
        }
    }
    canvas.drawCircle(cx, cy, r, ringPaint)

    // Inner Button Circle
    val fillPaint = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.FILL
        color = if (isConnected) {
            accentColor.toArgb()
        } else {
            Color(0xFF161821).toArgb()
        }
    }
    canvas.drawCircle(cx, cy, r - 6f, fillPaint)
}

// -------------------------------------------------------------
// Accurate Continents Polyline Array
// -------------------------------------------------------------
private val CONTINENTS = listOf(
    // Europe
    floatArrayOf(
        71f, 28f,  70f, 20f,  62f, 5f,   58f, 8f,   55f, 12f,  54f, 9f,   50f, 2f,
        44f, -1f,  43f, -9f,  37f, -9f,  36f, -5f,  37f, -2f,  41f, 3f,   43f, 6f,
        44f, 8f,   41f, 14f,  38f, 16f,  40f, 18f,  42f, 13f,  45f, 12f,  45f, 14f,
        42f, 19f,  40f, 20f,  37f, 22f,  38f, 24f,  40f, 23f,  41f, 28f,  44f, 29f,
        46f, 31f,  46f, 35f,  44f, 38f,  47f, 39f,  53f, 31f,  55f, 21f,  59f, 25f,
        60f, 29f,  65f, 25f,  69f, 31f,  71f, 28f
    ),
    // Siberia & Asia
    floatArrayOf(
        71f, 28f,  69f, 31f,  69f, 60f,  73f, 73f,  73f, 80f,  77f, 105f, 75f, 113f,
        70f, 135f, 72f, 150f, 69f, 170f, 66f, 170f, 60f, 163f, 56f, 162f, 51f, 156f,
        44f, 135f, 42f, 131f, 40f, 128f, 40f, 120f, 32f, 121f, 22f, 114f, 21f, 108f,
        22f, 100f, 28f, 97f,  35f, 75f,  45f, 80f,  50f, 85f,  55f, 60f,  55f, 45f,
        60f, 40f,  67f, 44f,  68f, 38f,  71f, 28f
    ),
    // North America
    floatArrayOf(
        71f, -156f, 71f, -130f, 69f, -115f, 68f, -90f,  63f, -80f,  55f, -82f,
        51f, -80f,  55f, -78f,  62f, -75f,  60f, -64f,  52f, -56f,  47f, -53f,
        44f, -64f,  41f, -71f,  35f, -75f,  30f, -81f,  25f, -80f,  28f, -82f,
        30f, -88f,  29f, -94f,  26f, -97f,  21f, -97f,  18f, -95f,  15f, -92f,
        14f, -88f,  10f, -83f,  8f, -77f,   14f, -92f,  18f, -104f, 23f, -106f,
        23f, -110f, 30f, -114f, 32f, -117f, 38f, -123f, 46f, -124f, 54f, -130f,
        59f, -140f, 60f, -149f, 56f, -159f, 58f, -162f, 65f, -168f, 71f, -156f
    ),
    // South America
    floatArrayOf(
        12f, -72f,  11f, -63f,  9f, -60f,   5f, -52f,   -2f, -44f,  -5f, -35f,
        -8f, -35f,  -13f, -39f, -22f, -41f, -24f, -46f, -33f, -52f, -38f, -57f,
        -45f, -65f, -52f, -68f, -55f, -66f, -53f, -73f, -46f, -75f, -37f, -73f,
        -20f, -70f, -12f, -77f, -4f, -81f,  2f, -78f,   8f, -77f,   12f, -72f
    ),
    // Africa
    floatArrayOf(
        37f, 10f,  36f, -5f,  32f, -9f,  28f, -13f, 21f, -17f, 15f, -17f,
        11f, -15f, 5f, -2f,   4f, 9f,    -1f, 9f,   -5f, 12f,  -12f, 13f,
        -16f, 12f, -23f, 14f, -29f, 17f, -34f, 18f, -34f, 26f, -30f, 31f,
        -25f, 33f, -16f, 40f, -10f, 40f, -3f, 40f,  4f, 48f,  11f, 51f,
        12f, 30f,  12f, 15f,  14f, 0f,   22f, 37f,  31f, 32f,  37f, 10f
    ),
    // Australia
    floatArrayOf(
        -11f, 142f, -15f, 145f, -20f, 148f, -25f, 153f, -30f, 153f, -37f, 150f,
        -38f, 145f, -38f, 140f, -35f, 137f, -35f, 134f, -32f, 132f, -32f, 125f,
        -34f, 120f, -35f, 115f, -30f, 115f, -25f, 113f, -20f, 119f, -15f, 124f,
        -14f, 129f, -12f, 136f, -11f, 142f
    )
)
