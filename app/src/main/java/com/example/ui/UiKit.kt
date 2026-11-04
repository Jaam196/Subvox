package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.State
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import com.example.ui.theme.AppThemeSpec
import com.example.ui.theme.CardDecor
import com.example.ui.theme.LocalAppTheme
import com.example.ui.theme.LocalThemeAnimations
import com.example.ui.theme.ThemeKind
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Pulso 0..1 compartido por los adornos animados (fijo en 1 si las animaciones están apagadas). */
@Composable
private fun rememberDecorPulse(periodMs: Int = 2400): State<Float> {
    return if (LocalThemeAnimations.current) {
        rememberInfiniteTransition(label = "decorPulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "decorPulseValue"
        )
    } else {
        remember { mutableStateOf(1f) }
    }
}

private fun DrawScope.drawHudBrackets(spec: AppThemeSpec, pulse: Float) {
    val arm = 16.dp.toPx()
    val sw = 2.dp.toPx()
    val cut = (if (spec.kind == ThemeKind.NEON_STREET) 16.dp else 14.dp).toPx()
    val main = spec.accentA.copy(alpha = 0.55f + 0.45f * pulse)
    val second = spec.accentB.copy(alpha = 0.35f + 0.35f * pulse)
    val w = size.width
    val h = size.height
    // Esquinas rectas (arriba-izquierda y abajo-derecha o al revés según el tema)
    val topLeftIsSharp = spec.kind == ThemeKind.NEON_STREET
    if (topLeftIsSharp) {
        drawLine(main, Offset(0f, 0f), Offset(arm, 0f), strokeWidth = sw)
        drawLine(main, Offset(0f, 0f), Offset(0f, arm), strokeWidth = sw)
        drawLine(main, Offset(w, h), Offset(w - arm, h), strokeWidth = sw)
        drawLine(main, Offset(w, h), Offset(w, h - arm), strokeWidth = sw)
        drawLine(second, Offset(w - cut, 0f), Offset(w, cut), strokeWidth = sw)
        drawLine(second, Offset(0f, h - cut), Offset(cut, h), strokeWidth = sw)
    } else {
        drawLine(main, Offset(w, 0f), Offset(w - arm, 0f), strokeWidth = sw)
        drawLine(main, Offset(w, 0f), Offset(w, arm), strokeWidth = sw)
        drawLine(main, Offset(0f, h), Offset(arm, h), strokeWidth = sw)
        drawLine(main, Offset(0f, h), Offset(0f, h - arm), strokeWidth = sw)
        drawLine(second, Offset(0f, cut), Offset(cut, 0f), strokeWidth = sw)
        drawLine(second, Offset(w, h - cut), Offset(w - cut, h), strokeWidth = sw)
    }
}

/** Tarjeta base de SubVox: forma, borde y adornos dependen del tema activo. */
@Composable
fun SubVoxCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val spec = LocalAppTheme.current
    val pulse = rememberDecorPulse()
    val effectiveBorder = border ?: when (spec.cardDecor) {
        CardDecor.SOFT -> BorderStroke(1.dp, spec.colorScheme.outlineVariant.copy(alpha = 0.7f))
        CardDecor.GLOW -> BorderStroke(
            1.5.dp,
            Brush.linearGradient(listOf(spec.accentA.copy(alpha = 0.9f), spec.accentB.copy(alpha = 0.9f)))
        )
        CardDecor.BRACKETS -> BorderStroke(1.dp, spec.accentA.copy(alpha = 0.30f))
    }
    val decorModifier = if (spec.cardDecor == CardDecor.BRACKETS) {
        Modifier.drawWithContent {
            drawContent()
            drawHudBrackets(spec, pulse.value)
        }
    } else {
        Modifier
    }
    Surface(
        modifier = modifier.fillMaxWidth().then(decorModifier),
        shape = MaterialTheme.shapes.large,
        color = containerColor.copy(alpha = containerColor.alpha * spec.cardAlpha),
        border = effectiveBorder
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Botones que adoptan la forma del tema (los de Material siempre son píldoras)
// ---------------------------------------------------------------------------------------------

@Composable
private fun themedButtonShape(): androidx.compose.ui.graphics.Shape {
    val spec = LocalAppTheme.current
    return when (spec.cardDecor) {
        CardDecor.BRACKETS -> MaterialTheme.shapes.small
        CardDecor.GLOW -> RoundedCornerShape(14.dp)
        CardDecor.SOFT -> ButtonDefaults.shape
    }
}

@Composable
private fun themedButtonBorder(): BorderStroke? {
    val spec = LocalAppTheme.current
    return if (spec.cardDecor == CardDecor.BRACKETS) {
        BorderStroke(1.dp, spec.accentA.copy(alpha = 0.7f))
    } else {
        null
    }
}

@Composable
fun SubVoxButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = themedButtonShape(),
        border = themedButtonBorder(),
        content = content
    )
}

@Composable
fun SubVoxTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = themedButtonShape(),
        border = themedButtonBorder(),
        content = content
    )
}

@Composable
fun SubVoxOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = themedButtonShape(),
        content = content
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 0.dp)
    )
}

@Composable
fun ScreenHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val spec = LocalAppTheme.current
    Column(modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        ThemedTitle(text = title, spec = spec)
        if (spec.kind == ThemeKind.PURPLE_UNIT) {
            SyncBar(spec = spec, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Título con efecto propio según el tema: glitch, degradado o brillo. */
@Composable
private fun ThemedTitle(text: String, spec: AppThemeSpec) {
    val style = MaterialTheme.typography.headlineMedium
    val color = MaterialTheme.colorScheme.onBackground
    when (spec.kind) {
        ThemeKind.NEON_STREET -> {
            val glitch: State<Float> = if (LocalThemeAnimations.current) {
                rememberInfiniteTransition(label = "titleGlitch").animateFloat(
                    initialValue = 0f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 3600
                            0f at 0
                            0f at 2900
                            1f at 2960
                            0f at 3030
                            0.6f at 3200
                            0f at 3280
                        }
                    ),
                    label = "titleGlitchValue"
                )
            } else {
                remember { mutableStateOf(0f) }
            }
            Box {
                Text(
                    text = text, style = style.copy(shadow = null), color = spec.accentC.copy(alpha = 0.75f),
                    modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset((-(1.5f + 6f * glitch.value)).dp.roundToPx(), 0) }
                )
                Text(
                    text = text, style = style.copy(shadow = null), color = spec.accentB.copy(alpha = 0.75f),
                    modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset((1.5f + 6f * glitch.value).dp.roundToPx(), 0) }
                )
                Text(text = text, style = style, color = spec.accentA)
            }
        }
        ThemeKind.SYNTHWAVE -> {
            Text(
                text = text,
                style = style.copy(
                    brush = Brush.horizontalGradient(listOf(spec.accentA, spec.accentC, spec.accentB))
                )
            )
        }
        ThemeKind.PURPLE_UNIT -> {
            Text(text = text, style = style, color = color)
        }
        else -> {
            Text(text = text, style = style, color = color)
        }
    }
}

/** Barra de "sincronización" segmentada que se va llenando, para el tema de cabina de mando. */
@Composable
private fun SyncBar(spec: AppThemeSpec, modifier: Modifier = Modifier) {
    val progress: State<Float> = if (LocalThemeAnimations.current) {
        rememberInfiniteTransition(label = "syncBar").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
            label = "syncBarValue"
        )
    } else {
        remember { mutableStateOf(0.62f) }
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
    ) {
        val segments = 28
        val gap = 3.dp.toPx()
        val segW = (size.width - gap * (segments - 1)) / segments
        val lit = (progress.value * (segments + 4)).toInt()
        for (i in 0 until segments) {
            val on = i < lit
            val color = when {
                i >= segments - 4 -> spec.accentC
                else -> spec.accentB
            }
            drawRect(
                color = if (on) color.copy(alpha = 0.9f) else spec.accentA.copy(alpha = 0.18f),
                topLeft = Offset(i * (segW + gap), 0f),
                size = Size(segW, size.height)
            )
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * Deslizador que solo guarda el valor al soltar (evita escribir en DataStore en cada píxel).
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueCommitted: (Float) -> Unit,
    valueLabel: (Float) -> String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    steps: Int = 0
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueLabel(local),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = local.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { local = it },
            onValueChangeFinished = { onValueCommitted(local) },
            valueRange = valueRange,
            steps = steps
        )
    }
}

/** Fila horizontal de opciones tipo "chip" (una seleccionada). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val pillShape = if (LocalAppTheme.current.cardDecor == CardDecor.BRACKETS) {
        MaterialTheme.shapes.extraSmall
    } else {
        RoundedCornerShape(50)
    }
    Row(
        modifier = modifier
            .clip(pillShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
