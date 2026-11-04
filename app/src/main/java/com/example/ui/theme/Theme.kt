package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Tema de SubVox. [themeId] elige uno de los seis temas (colores, tipografía, formas, adornos,
 * fondo animado y panel flotante). Aurora sigue el modo claro/oscuro del sistema.
 */
@Composable
fun SubVoxTheme(
    themeId: String = AppThemes.AURORA_ID,
    animationsEnabled: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val spec = remember(themeId, darkTheme) { AppThemes.forId(themeId, darkTheme) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !spec.isDark
                controller.isAppearanceLightNavigationBars = !spec.isDark
            }
        }
    }
    CompositionLocalProvider(
        LocalAppTheme provides spec,
        LocalThemeAnimations provides animationsEnabled
    ) {
        MaterialTheme(
            colorScheme = spec.colorScheme,
            typography = spec.typography,
            shapes = spec.shapes,
            content = content
        )
    }
}
