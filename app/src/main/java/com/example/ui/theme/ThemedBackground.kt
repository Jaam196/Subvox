package com.example.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val TAU = (2.0 * PI).toFloat()

private fun frac(x: Float): Float = x - floor(x)
private fun wave(t: Float, cycles: Int, offset: Float): Float = sin(TAU * (t * cycles + offset))
private fun wave01(t: Float, cycles: Int, offset: Float): Float = 0.5f + 0.5f * wave(t, cycles, offset)

private fun periodMs(kind: ThemeKind): Int = when (kind) {
    ThemeKind.AURORA -> 42_000
    ThemeKind.SAKURA -> 32_000
    ThemeKind.ABYSS -> 38_000
    ThemeKind.SYNTHWAVE -> 9_000
    ThemeKind.NEON_STREET -> 20_000
    ThemeKind.PURPLE_UNIT -> 24_000
}

/**
 * Fondo animado del tema. Todo se dibuja en un único Canvas y la animación solo invalida el
 * dibujo (no recompone la pantalla). Con [animated] = false se queda quieto en [frozenPhase].
 */
@Composable
fun ThemedBackground(
    spec: AppThemeSpec,
    modifier: Modifier = Modifier,
    animated: Boolean = LocalThemeAnimations.current,
    frozenPhase: Float = 0.3f
) {
    val phase: State<Float> = if (animated) {
        val transition = rememberInfiniteTransition(label = "themeBackground")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = periodMs(spec.kind), easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "themePhase"
        )
    } else {
        remember(frozenPhase) { mutableStateOf(frozenPhase) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(spec.colorScheme.background)
            .drawWithCache {
                val scene = createScene(spec, size.width, size.height, density)
                onDrawBehind { scene.draw(this, phase.value) }
            }
    )
}

private abstract class Scene {
    abstract fun draw(scope: DrawScope, t: Float)
}

private fun createScene(spec: AppThemeSpec, w: Float, h: Float, dp: Float): Scene = when (spec.kind) {
    ThemeKind.AURORA -> AuroraScene(spec, w, h)
    ThemeKind.SAKURA -> SakuraScene(spec, w, h, dp)
    ThemeKind.ABYSS -> AbyssScene(spec, w, h, dp)
    ThemeKind.SYNTHWAVE -> SynthwaveScene(spec, w, h, dp)
    ThemeKind.NEON_STREET -> NeonStreetScene(spec, w, h, dp)
    ThemeKind.PURPLE_UNIT -> PurpleUnitScene(spec, w, h, dp)
}

// ==============================================================================================
// AURORA: tres manchas de luz que se mueven despacio
// ==============================================================================================

private class AuroraScene(val spec: AppThemeSpec, val w: Float, val h: Float) : Scene() {
    override fun draw(scope: DrawScope, t: Float) = with(scope) {
        val scheme = spec.colorScheme
        drawRect(
            brush = Brush.verticalGradient(
                listOf(scheme.background, if (spec.isDark) scheme.surfaceContainerLowest else scheme.surfaceContainerLow)
            )
        )
        val a = if (spec.isDark) 0.30f else 0.20f
        fun blob(color: Color, cx: Float, cy: Float, phase: Float, radius: Float) {
            val center = Offset(
                w * (cx + 0.28f * wave(t, 1, phase)),
                h * (cy + 0.14f * wave(t, 2, phase + 0.25f))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = a), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
        blob(spec.accentA, 0.25f, 0.20f, 0.00f, w * 0.85f)
        blob(spec.accentB, 0.80f, 0.55f, 0.33f, w * 0.75f)
        blob(spec.accentC, 0.35f, 0.90f, 0.66f, w * 0.80f)
    }
}

// ==============================================================================================
// SAKURA: pétalos que caen con vaivén
// ==============================================================================================

private class SakuraScene(val spec: AppThemeSpec, val w: Float, val h: Float, val dp: Float) : Scene() {
    private val count = 30
    private val rnd = Random(77)
    private val x0 = FloatArray(count) { rnd.nextFloat() }
    private val phase = FloatArray(count) { rnd.nextFloat() }
    private val petalSize = FloatArray(count) { (4f + rnd.nextFloat() * 6f) * dp }
    private val sway = FloatArray(count) { (14f + rnd.nextFloat() * 26f) * dp }
    private val speed = IntArray(count) { 1 + rnd.nextInt(2) }
    private val tone = FloatArray(count) { rnd.nextFloat() }

    private val petalLight = Color(0xFFFFC2D6)
    private val petalDeep = Color(0xFFF58AAE)

    override fun draw(scope: DrawScope, t: Float) = with(scope) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFFFFF7FA), Color(0xFFFFEAF1), Color(0xFFFFE0EA))
            )
        )
        // Luna suave, arriba a la derecha
        val moon = Offset(w * 0.82f, h * 0.12f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFFFFF).copy(alpha = 0.95f), Color(0xFFFFD6E4).copy(alpha = 0.35f), Color.Transparent),
                center = moon, radius = w * 0.34f
            ),
            radius = w * 0.34f, center = moon
        )
        // Ramas de tinta tenues en la esquina superior izquierda
        val branch = Color(0xFF8E6BBF).copy(alpha = 0.10f)
        drawLine(branch, Offset(0f, h * 0.05f), Offset(w * 0.45f, h * 0.02f), strokeWidth = 3f * dp)
        drawLine(branch, Offset(w * 0.18f, h * 0.035f), Offset(w * 0.30f, h * 0.10f), strokeWidth = 2f * dp)

        for (i in 0 until count) {
            val y = frac(t * speed[i] + phase[i]) * (h + 60f * dp) - 30f * dp
            val x = x0[i] * w + sway[i] * wave(t, 3, phase[i] * 5f)
            val rot = 360f * frac(t * 2f * speed[i] + phase[i] * 3f)
            val s = petalSize[i]
            val color = lerp(petalLight, petalDeep, tone[i]).copy(alpha = 0.55f + 0.35f * tone[i])
            rotate(degrees = rot, pivot = Offset(x, y)) {
                drawOval(color = color, topLeft = Offset(x - s, y - s * 0.55f), size = Size(2f * s, 1.1f * s))
                drawOval(
                    color = Color.White.copy(alpha = 0.35f),
                    topLeft = Offset(x - s * 0.55f, y - s * 0.25f),
                    size = Size(s * 0.9f, s * 0.4f)
                )
            }
        }
    }
}

// ==============================================================================================
// ABISMO: rayos de luz que ondean, burbujas y plancton luminoso
// ==============================================================================================

private class AbyssScene(val spec: AppThemeSpec, val w: Float, val h: Float, val dp: Float) : Scene() {
    private val bubbles = 24
    private val plankton = 38
    private val rnd = Random(404)
    private val bx = FloatArray(bubbles) { rnd.nextFloat() }
    private val bp = FloatArray(bubbles) { rnd.nextFloat() }
    private val br = FloatArray(bubbles) { (2f + rnd.nextFloat() * 6f) * dp }
    private val bs = IntArray(bubbles) { 1 + rnd.nextInt(3) }
    private val px = FloatArray(plankton) { rnd.nextFloat() }
    private val py = FloatArray(plankton) { rnd.nextFloat() }
    private val pp = FloatArray(plankton) { rnd.nextFloat() }
    private val pc = BooleanArray(plankton) { rnd.nextBoolean() }
    private val rayX = floatArrayOf(0.10f, 0.32f, 0.58f, 0.82f)
    private val rayW = floatArrayOf(0.10f, 0.14f, 0.09f, 0.13f)

    override fun draw(scope: DrawScope, t: Float) = with(scope) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF06304F), Color(0xFF04203A), Color(0xFF020B14))
            )
        )
        // Rayos de luz desde la superficie
        for (i in rayX.indices) {
            val sway = 0.05f * wave(t, 1, i * 0.25f)
            val x = (rayX[i] + sway) * w
            val top = rayW[i] * w
            val skew = (0.18f + 0.06f * wave(t, 2, i * 0.4f)) * w
            val path = Path().apply {
                moveTo(x, 0f)
                lineTo(x + top, 0f)
                lineTo(x + top + skew, h * 0.78f)
                lineTo(x + skew * 0.6f, h * 0.78f)
                close()
            }
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFBFF4FF).copy(alpha = 0.13f), Color.Transparent),
                    startY = 0f, endY = h * 0.78f
                )
            )
        }
        // Plancton luminoso
        for (i in 0 until plankton) {
            val a = 0.15f + 0.65f * wave01(t, 2 + i % 3, pp[i])
            val c = if (pc[i]) spec.accentA else spec.accentB
            val x = px[i] * w + 10f * dp * wave(t, 1, pp[i])
            val y = py[i] * h + 8f * dp * wave(t, 2, pp[i] + 0.3f)
            drawCircle(c.copy(alpha = a * 0.25f), radius = 5f * dp, center = Offset(x, y))
            drawCircle(c.copy(alpha = a), radius = 1.6f * dp, center = Offset(x, y))
        }
        // Burbujas que suben
        for (i in 0 until bubbles) {
            val y = h + br[i] - frac(t * bs[i] + bp[i]) * (h + 2f * br[i])
            val x = bx[i] * w + 8f * dp * wave(t, 4, bp[i])
            drawCircle(
                color = Color(0xFFCFF8FF).copy(alpha = 0.40f),
                radius = br[i], center = Offset(x, y), style = Stroke(width = 1.1f * dp)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.55f),
                radius = br[i] * 0.22f, center = Offset(x - br[i] * 0.35f, y - br[i] * 0.35f)
            )
        }
        // Oscurecer el fondo marino
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, Color(0xFF020B14).copy(alpha = 0.7f)),
                startY = h * 0.6f, endY = h
            )
        )
    }
}

// ==============================================================================================
// ATARDECER SINTÉTICO: sol con rendijas, rejilla en perspectiva, estrellas
// ==============================================================================================

private class SynthwaveScene(val spec: AppThemeSpec, val w: Float, val h: Float, val dp: Float) : Scene() {
    private val horizon = h * 0.56f
    private val rnd = Random(1984)
    private val stars = 46
    private val sx = FloatArray(stars) { rnd.nextFloat() }
    private val sy = FloatArray(stars) { rnd.nextFloat() * 0.85f }
    private val sp = FloatArray(stars) { rnd.nextFloat() }

    private val skyStops = listOf(
        0.00f to Color(0xFF0B0524),
        0.45f to Color(0xFF2B0B5E),
        0.80f to Color(0xFF8A1C7C),
        1.00f to Color(0xFFFF5E9C)
    )

    private fun skyColorAt(y: Float): Color {
        val f = (y / horizon).coerceIn(0f, 1f)
        for (i in 0 until skyStops.size - 1) {
            val (f0, c0) = skyStops[i]
            val (f1, c1) = skyStops[i + 1]
            if (f <= f1) return lerp(c0, c1, ((f - f0) / (f1 - f0)).coerceIn(0f, 1f))
        }
        return skyStops.last().second
    }

    override fun draw(scope: DrawScope, t: Float) = with(scope) {
        // Cielo
        drawRect(
            brush = Brush.verticalGradient(
                *skyStops.toTypedArray(),
                startY = 0f, endY = horizon
            ),
            size = Size(w, horizon)
        )
        // Estrellas parpadeantes
        for (i in 0 until stars) {
            val a = 0.25f + 0.75f * wave01(t, 3 + i % 4, sp[i])
            drawCircle(
                Color.White.copy(alpha = a * 0.8f),
                radius = (0.8f + 1.2f * sp[i]) * dp,
                center = Offset(sx[i] * w, sy[i] * horizon * 0.8f)
            )
        }
        // Sol
        val sunR = w * 0.30f
        val sunC = Offset(w * 0.5f, horizon - sunR * 0.12f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(spec.accentA.copy(alpha = 0.45f), Color.Transparent),
                center = sunC, radius = sunR * 1.9f
            ),
            radius = sunR * 1.9f, center = sunC
        )
        val sunPath = Path().apply {
            addOval(Rect(sunC.x - sunR, sunC.y - sunR, sunC.x + sunR, sunC.y + sunR))
        }
        clipPath(sunPath) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(spec.accentC, Color(0xFFFF6FA8), spec.accentA),
                    startY = sunC.y - sunR, endY = sunC.y + sunR
                ),
                topLeft = Offset(sunC.x - sunR, sunC.y - sunR),
                size = Size(sunR * 2f, sunR * 2f)
            )
            // Rendijas que bajan por el sol
            val bars = 7
            for (i in 0 until bars) {
                val f = frac(i.toFloat() / bars + t * 1f)
                val y = sunC.y - sunR * 0.05f + f * sunR * 1.1f
                val thick = (1.5f + f * 9f) * dp
                drawRect(
                    color = skyColorAt(y),
                    topLeft = Offset(sunC.x - sunR, y),
                    size = Size(sunR * 2f, thick)
                )
            }
        }
        // Suelo
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF2A0A55), Color(0xFF14062F), Color(0xFF0D0524)),
                startY = horizon, endY = h
            ),
            topLeft = Offset(0f, horizon), size = Size(w, h - horizon)
        )
        // Líneas horizontales que avanzan hacia el espectador
        val lines = 11
        for (i in 0 until lines) {
            val z = frac(i.toFloat() / lines + t)
            val y = horizon + (h - horizon) * z * z
            val a = (0.15f + 0.85f * z).coerceIn(0f, 1f)
            drawLine(
                color = spec.accentB.copy(alpha = 0.75f * a),
                start = Offset(0f, y), end = Offset(w, y),
                strokeWidth = (0.8f + 1.6f * z) * dp
            )
        }
        // Líneas verticales en perspectiva
        val cols = 14
        for (j in -cols..cols) {
            val xTop = w * 0.5f + j * w * 0.014f
            val xBottom = w * 0.5f + j * w * 0.24f
            drawLine(
                color = spec.accentA.copy(alpha = 0.55f),
                start = Offset(xTop, horizon), end = Offset(xBottom, h),
                strokeWidth = 1.1f * dp
            )
        }
        // Resplandor del horizonte
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, spec.accentA.copy(alpha = 0.55f), Color.Transparent),
                startY = horizon - 20f * dp, endY = horizon + 26f * dp
            ),
            topLeft = Offset(0f, horizon - 20f * dp), size = Size(w, 46f * dp)
        )
        // Oscurecer la parte baja para que el texto se lea
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, Color(0xFF0D0524).copy(alpha = 0.55f)),
                startY = h * 0.7f, endY = h
            ),
            topLeft = Offset(0f, h * 0.7f), size = Size(w, h * 0.3f)
        )
    }
}

// ==============================================================================================
// NEÓN CALLEJERO: lluvia de datos, skyline con ventanas, barrido de escáner y glitch
// ==============================================================================================

private class NeonStreetScene(val spec: AppThemeSpec, val w: Float, val h: Float, val dp: Float) : Scene() {
    private val rnd = Random(2077)
    private val streams = 16
    private val stX = FloatArray(streams) { rnd.nextFloat() }
    private val stP = FloatArray(streams) { rnd.nextFloat() }
    private val stS = IntArray(streams) { 1 + rnd.nextInt(3) }
    private val stL = FloatArray(streams) { (60f + rnd.nextFloat() * 160f) * dp }
    private val stCyan = BooleanArray(streams) { rnd.nextInt(3) != 0 }

    // Skyline
    private class Building(val x: Float, val width: Float, val height: Float, val windows: List<Triple<Float, Float, Float>>)

    private val buildings: List<Building> = run {
        val list = mutableListOf<Building>()
        var x = -10f * dp
        val maxH = h * 0.2f
        while (x < w) {
            val bw = (26f + rnd.nextFloat() * 40f) * dp
            val bh = maxH * (0.35f + rnd.nextFloat() * 0.65f)
            val wins = mutableListOf<Triple<Float, Float, Float>>()
            var wy = 8f * dp
            while (wy < bh - 6f * dp) {
                var wx = 6f * dp
                while (wx < bw - 8f * dp) {
                    if (rnd.nextFloat() < 0.55f) wins += Triple(wx, wy, rnd.nextFloat())
                    wx += 9f * dp
                }
                wy += 11f * dp
            }
            list += Building(x, bw, bh, wins)
            x += bw + (2f + rnd.nextFloat() * 6f) * dp
        }
        list
    }

    private val gridStep = 38f * dp

    override fun draw(scope: DrawScope, t: Float) = with(scope) {
        val yellow = spec.accentA
        val cyan = spec.accentB
        val red = spec.accentC

        drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF0A0A0F), Color(0xFF07070A), Color(0xFF0C0A06))))

        // Rejilla fina que se desplaza
        val offY = frac(t * 2f) * gridStep
        var gy = -gridStep + offY
        while (gy < h) {
            drawLine(yellow.copy(alpha = 0.05f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f * dp)
            gy += gridStep
        }
        var gx = 0f
        while (gx < w) {
            drawLine(yellow.copy(alpha = 0.04f), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f * dp)
            gx += gridStep
        }

        // Skyline iluminado en la parte baja
        for (b in buildings) {
            val top = h - b.height
            drawRect(Color(0xFF0E0E15), Offset(b.x, top), Size(b.width, b.height))
            drawRect(yellow.copy(alpha = 0.10f), Offset(b.x, top), Size(b.width, 1.5f * dp))
            for ((wx, wy, ph) in b.windows) {
                val on = wave01(t, 2 + (ph * 5f).toInt(), ph)
                if (on > 0.35f) {
                    val c = if (ph > 0.8f) cyan else if (ph < 0.12f) red else yellow
                    drawRect(
                        c.copy(alpha = 0.15f + 0.5f * on),
                        Offset(b.x + wx, top + wy), Size(3f * dp, 4.5f * dp)
                    )
                }
            }
        }

        // Lluvia de datos: trazos verticales con cabeza brillante
        for (i in 0 until streams) {
            val head = frac(t * stS[i] + stP[i]) * (h + stL[i] * 1.5f) - stL[i] * 0.2f
            val x = stX[i] * w
            val c = if (stCyan[i]) cyan else yellow
            drawLine(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, c.copy(alpha = 0.55f)),
                    startY = head - stL[i], endY = head
                ),
                start = Offset(x, head - stL[i]), end = Offset(x, head),
                strokeWidth = 1.6f * dp
            )
            drawCircle(c.copy(alpha = 0.9f), radius = 1.8f * dp, center = Offset(x, head))
        }

        // Barrido del escáner
        val scanY = frac(t * 2f) * (h + 120f * dp) - 60f * dp
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, cyan.copy(alpha = 0.10f), Color.Transparent),
                startY = scanY - 60f * dp, endY = scanY + 60f * dp
            ),
            topLeft = Offset(0f, scanY - 60f * dp), size = Size(w, 120f * dp)
        )

        // Marcas diagonales en las esquinas
        val slash = Path().apply {
            moveTo(w, 0f); lineTo(w - 96f * dp, 0f); lineTo(w, 96f * dp); close()
        }
        drawPath(slash, yellow.copy(alpha = 0.10f))
        drawLine(yellow.copy(alpha = 0.35f), Offset(w - 96f * dp, 0f), Offset(w, 96f * dp), strokeWidth = 1.5f * dp)
        val slash2 = Path().apply {
            moveTo(0f, h * 0.62f); lineTo(34f * dp, h * 0.62f); lineTo(0f, h * 0.62f + 34f * dp); close()
        }
        drawPath(slash2, cyan.copy(alpha = 0.18f))

        // Glitch: 5 sacudidas por ciclo
        val cycle = t * 5f
        val local = frac(cycle)
        if (local < 0.05f || (local in 0.5f..0.54f)) {
            val seed = floor(cycle).toInt()
            val gr = Random(seed * 31 + 7)
            for (k in 0 until 3) {
                val gy2 = gr.nextFloat() * h
                val gh = (6f + gr.nextFloat() * 22f) * dp
                val shift = (gr.nextFloat() - 0.5f) * 36f * dp
                val gc = if (k % 2 == 0) cyan else red
                translate(left = shift) {
                    drawRect(gc.copy(alpha = 0.16f), Offset(0f, gy2), Size(w, gh))
                }
            }
        }

        // Viñeta
        drawRect(
            brush = Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = Offset(w / 2f, h / 2f), radius = max(w, h) * 0.75f
            )
        )
    }
}

// ==============================================================================================
// UNIDAD PÚRPURA: rejilla hexagonal, radar giratorio, franjas de alerta y barrido verde
// ==============================================================================================

private class PurpleUnitScene(val spec: AppThemeSpec, val w: Float, val h: Float, val dp: Float) : Scene() {
    private val hexR = 26f * dp
    private val hexPath = Path()
    private val litPaths = mutableListOf<Path>()
    private val litPhase = mutableListOf<Float>()
    private val stripesPath = Path()
    private val stripePeriod = 28f * dp
    private val bandH = 9f * dp

    private fun hexAt(cx: Float, cy: Float, r: Float, into: Path) {
        for (k in 0 until 6) {
            val ang = (PI / 3.0 * k).toFloat() + (PI / 6.0).toFloat()
            val x = cx + r * cos(ang)
            val y = cy + r * sin(ang)
            if (k == 0) into.moveTo(x, y) else into.lineTo(x, y)
        }
        into.close()
    }

    init {
        val rnd = Random(1)
        val dx = hexR * sqrt(3f)
        val dy = hexR * 1.5f
        var row = 0
        var y = 0f
        var counter = 0
        while (y < h + hexR) {
            var x = if (row % 2 == 0) 0f else dx / 2f
            while (x < w + hexR) {
                hexAt(x, y, hexR * 0.94f, hexPath)
                counter++
                if (counter % 23 == 0 && litPaths.size < 9) {
                    val p = Path()
                    hexAt(x, y, hexR * 0.86f, p)
                    litPaths += p
                    litPhase += rnd.nextFloat()
                }
                x += dx
            }
            y += dy
            row++
        }
        // Franjas diagonales de aviso (se repiten cada stripePeriod)
        var sx = -stripePeriod * 2f
        while (sx < w + stripePeriod * 2f) {
            stripesPath.moveTo(sx, bandH)
            stripesPath.lineTo(sx + stripePeriod * 0.5f, bandH)
            stripesPath.lineTo(sx + stripePeriod * 0.5f + bandH, 0f)
            stripesPath.lineTo(sx + bandH, 0f)
            stripesPath.close()
            sx += stripePeriod
        }
    }

    override fun draw(scope: DrawScope, t: Float) = with(scope) {
        val purple = spec.accentA
        val green = spec.accentB
        val orange = spec.accentC

        drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF0C0614), Color(0xFF150A26), Color(0xFF0A0512))))

        // Rejilla hexagonal
        drawPath(hexPath, purple.copy(alpha = 0.13f), style = Stroke(width = 1f * dp))
        for (i in litPaths.indices) {
            val a = wave01(t, 2 + i % 3, litPhase[i])
            drawPath(litPaths[i], (if (i % 3 == 0) green else purple).copy(alpha = 0.04f + 0.20f * a))
        }

        // Radar giratorio en la esquina superior derecha
        val c = Offset(w * 0.90f, h * 0.15f)
        val r = w * 0.40f
        rotate(degrees = 360f * t * 2f, pivot = c) {
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.78f to Color.Transparent,
                    1f to green.copy(alpha = 0.30f),
                    center = c
                ),
                radius = r, center = c
            )
        }
        drawCircle(green.copy(alpha = 0.22f), radius = r, center = c, style = Stroke(width = 1.2f * dp))
        drawCircle(green.copy(alpha = 0.16f), radius = r * 0.66f, center = c, style = Stroke(width = 1f * dp))
        drawCircle(green.copy(alpha = 0.12f), radius = r * 0.33f, center = c, style = Stroke(width = 1f * dp))
        drawLine(green.copy(alpha = 0.14f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 1f * dp)
        drawLine(green.copy(alpha = 0.14f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 1f * dp)
        // Anillos exteriores con arcos que giran en sentidos opuestos
        drawArc(
            color = orange.copy(alpha = 0.55f), startAngle = 360f * t * 3f, sweepAngle = 60f, useCenter = false,
            topLeft = Offset(c.x - r * 1.12f, c.y - r * 1.12f), size = Size(r * 2.24f, r * 2.24f),
            style = Stroke(width = 3f * dp)
        )
        drawArc(
            color = purple.copy(alpha = 0.55f), startAngle = -360f * t * 2f + 120f, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(c.x - r * 1.22f, c.y - r * 1.22f), size = Size(r * 2.44f, r * 2.44f),
            style = Stroke(width = 2f * dp)
        )

        // Barrido vertical de escaneo
        val sy = frac(t * 2f) * h
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, green.copy(alpha = 0.10f), Color.Transparent),
                startY = sy - 40f * dp, endY = sy + 40f * dp
            ),
            topLeft = Offset(0f, sy - 40f * dp), size = Size(w, 80f * dp)
        )
        drawLine(green.copy(alpha = 0.40f), Offset(0f, sy), Offset(w, sy), strokeWidth = 1.2f * dp)

        // Marcas de regla en el borde izquierdo
        var ty = 0f
        var n = 0
        while (ty < h) {
            val long = n % 5 == 0
            drawLine(
                purple.copy(alpha = if (long) 0.40f else 0.22f),
                Offset(0f, ty), Offset((if (long) 12f else 6f) * dp, ty), strokeWidth = 1f * dp
            )
            ty += 14f * dp
            n++
        }
        val markerY = frac(t * 3f) * h
        drawRect(orange.copy(alpha = 0.8f), Offset(0f, markerY - 1.5f * dp), Size(16f * dp, 3f * dp))

        // Franjas de aviso arriba y abajo, desplazándose
        val shift = frac(t * 4f) * stripePeriod
        clipRect(left = 0f, top = 0f, right = w, bottom = bandH) {
            drawRect(Color(0xFF120A1E), Offset.Zero, Size(w, bandH))
            translate(left = shift) { drawPath(stripesPath, orange.copy(alpha = 0.85f)) }
        }
        clipRect(left = 0f, top = h - bandH, right = w, bottom = h) {
            drawRect(Color(0xFF120A1E), Offset(0f, h - bandH), Size(w, bandH))
            translate(left = -shift, top = h - bandH) { drawPath(stripesPath, orange.copy(alpha = 0.85f)) }
        }

        // Viñeta
        drawRect(
            brush = Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.50f)),
                center = Offset(w / 2f, h / 2f), radius = max(w, h) * 0.78f
            )
        )
    }
}
