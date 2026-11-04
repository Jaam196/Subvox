package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppThemeSpec
import com.example.ui.theme.CardDecor
import com.example.ui.theme.LocalAppTheme
import com.example.ui.theme.LocalThemeAnimations
import com.example.ui.theme.ThemedBackground
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private enum class AppTab(val label: String, val icon: ImageVector) {
    LISTEN("Escuchar", Icons.Rounded.GraphicEq),
    VOICE("Voz", Icons.Rounded.RecordVoiceOver),
    READING("Lectura", Icons.Rounded.Subtitles),
    MORE("Más", Icons.Rounded.MoreHoriz)
}

/** Raíz de la interfaz de SubVox: fondo animado del tema + navegación inferior con cuatro pestañas. */
@Composable
fun SubVoxApp(viewModel: MainViewModel, platform: PlatformActions) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.LISTEN.name) }
    val current = AppTab.valueOf(selectedTab)
    val spec = LocalAppTheme.current

    Box(modifier = Modifier.fillMaxSize()) {
        ThemedBackground(spec = spec)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            bottomBar = {
                ThemedNavigationBar(
                    spec = spec,
                    current = current,
                    onSelect = { selectedTab = it.name }
                )
            }
        ) { innerPadding ->
            val contentModifier = Modifier.padding(innerPadding)
            when (current) {
                AppTab.LISTEN -> HomeScreen(vm = viewModel, platform = platform, modifier = contentModifier)
                AppTab.VOICE -> VoiceScreen(vm = viewModel, platform = platform, modifier = contentModifier)
                AppTab.READING -> ReadingScreen(vm = viewModel, platform = platform, modifier = contentModifier)
                AppTab.MORE -> MoreScreen(vm = viewModel, platform = platform, modifier = contentModifier)
            }
        }
    }
}

@Composable
private fun ThemedNavigationBar(spec: AppThemeSpec, current: AppTab, onSelect: (AppTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val animated = LocalThemeAnimations.current
    val sweep: State<Float> = if (animated && spec.cardDecor != CardDecor.SOFT) {
        rememberInfiniteTransition(label = "navSweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
            label = "navSweepValue"
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }

    NavigationBar(
        modifier = Modifier.drawWithContent {
            drawContent()
            if (spec.cardDecor != CardDecor.SOFT) {
                val sw = 2.dp.toPx()
                drawLine(spec.accentA.copy(alpha = 0.35f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = sw)
                // Destello que recorre la barra
                val len = size.width * 0.28f
                val x = sweep.value * (size.width + len) - len
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, spec.accentB, Color.Transparent),
                        startX = x, endX = x + len
                    ),
                    start = Offset(x, 0f), end = Offset(x + len, 0f), strokeWidth = sw
                )
            }
        },
        containerColor = scheme.surfaceContainer.copy(alpha = 0.94f)
    ) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = if (spec.cardDecor == CardDecor.BRACKETS) spec.accentA else scheme.onPrimaryContainer,
                    selectedTextColor = if (spec.cardDecor == CardDecor.BRACKETS) spec.accentA else scheme.onSurface,
                    indicatorColor = if (spec.cardDecor == CardDecor.BRACKETS) {
                        spec.accentA.copy(alpha = 0.16f)
                    } else {
                        scheme.primaryContainer
                    }
                )
            )
        }
    }
}
