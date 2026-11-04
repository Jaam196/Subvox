package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AppThemeSpec
import com.example.ui.theme.AppThemes
import com.example.ui.theme.CardDecor
import com.example.ui.theme.ThemedBackground

/** Pestaña "Temas": lista de los seis temas con vista previa viva del seleccionado. */
@Composable
fun ThemePickerSection(vm: MainViewModel) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()

    SubVoxCard {
        Text(
            text = "Elige cómo se ve SubVox",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Cada tema cambia todo: colores, letras, fondo animado, efectos y el panel flotante que aparece sobre el vídeo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchRow(
            title = "Animaciones del tema",
            subtitle = "Apágalas si notas el móvil lento o quieres ahorrar batería.",
            checked = settings.themeAnimationsEnabled,
            onCheckedChange = vm::setThemeAnimationsEnabled
        )
    }

    AppThemes.ALL_IDS.forEach { id ->
        val spec = remember(id, systemDark) { AppThemes.forId(id, systemDark) }
        ThemeCard(
            spec = spec,
            selected = settings.selectedThemeId == id,
            animate = settings.selectedThemeId == id && settings.themeAnimationsEnabled,
            onClick = { vm.setSelectedThemeId(id) }
        )
    }
}

@Composable
private fun ThemeCard(
    spec: AppThemeSpec,
    selected: Boolean,
    animate: Boolean,
    onClick: () -> Unit
) {
    val scheme = spec.colorScheme
    val shape = if (spec.cardDecor == CardDecor.BRACKETS) {
        CutCornerShape(topStart = 0.dp, topEnd = 22.dp, bottomEnd = 0.dp, bottomStart = 22.dp)
    } else {
        RoundedCornerShape(26.dp)
    }
    val border = if (selected) {
        BorderStroke(2.5.dp, spec.accentA)
    } else {
        BorderStroke(1.dp, scheme.outlineVariant)
    }
    val overlay = spec.overlay
    val mono = spec.overlayTypefaceMonospace

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(shape)
            .border(border, shape)
            .clickable(onClick = onClick)
    ) {
        ThemedBackground(spec = spec, animated = animate, frozenPhase = 0.32f)

        // Velo para que el texto se lea sobre cualquier fondo
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .background(scheme.background.copy(alpha = 0.28f))
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = spec.displayName,
                        style = spec.typography.titleLarge,
                        color = scheme.onBackground
                    )
                    Text(
                        text = spec.tagline,
                        style = spec.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Tema activo",
                        tint = spec.accentA,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(spec.accentA, spec.accentB, spec.accentC).forEach { c ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(c)
                    )
                }
            }

            // Maqueta del panel flotante con los colores y la forma del tema
            val panelShape = if (overlay.cornerRadiusDp <= 1f) {
                CutCornerShape(topStart = 0.dp, topEnd = 10.dp, bottomEnd = 0.dp, bottomStart = 10.dp)
            } else {
                RoundedCornerShape(overlay.cornerRadiusDp.coerceAtMost(18f).dp)
            }
            Row(
                modifier = Modifier
                    .clip(panelShape)
                    .background(Color(overlay.panelBackgroundColorArgb))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Color(overlay.secondaryAccentArgb))
                )
                Text(
                    text = "Hola, ¿cómo estás?",
                    color = Color(overlay.textPrimaryArgb),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = if (mono) FontFamily.Monospace else null
                )
                Text(
                    text = "⏸",
                    color = Color(overlay.primaryAccentArgb),
                    fontSize = 12.sp
                )
            }
        }
    }
}
