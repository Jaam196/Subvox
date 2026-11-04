package com.example.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.overlay.OverlayStyle

/** Familia visual del tema: decide el fondo animado y los adornos. */
enum class ThemeKind { AURORA, SAKURA, ABYSS, SYNTHWAVE, NEON_STREET, PURPLE_UNIT }

/** Estilo de tarjeta. */
enum class CardDecor {
    /** Tarjeta redondeada con borde fino. */
    SOFT,

    /** Borde con degradado de dos colores. */
    GLOW,

    /** Esquinas cortadas y marcas de esquina tipo HUD. */
    BRACKETS
}

/**
 * Todo lo que cambia con un tema: colores, formas, tipografía, adornos y el estilo del panel flotante.
 */
data class AppThemeSpec(
    val id: String,
    val displayName: String,
    val tagline: String,
    val kind: ThemeKind,
    val isDark: Boolean,
    val colorScheme: ColorScheme,
    val shapes: Shapes,
    val typography: Typography,
    val cardDecor: CardDecor,
    val accentA: Color,
    val accentB: Color,
    val accentC: Color,
    val cardAlpha: Float,
    val heroShape: CornerBasedShape,
    val overlay: OverlayStyle,
    /** true = el panel flotante cambia de color de borde con una animación suave. */
    val overlayAnimated: Boolean,
    val overlayTypefaceMonospace: Boolean
)

val LocalAppTheme = staticCompositionLocalOf<AppThemeSpec> { error("SubVoxTheme no está presente") }

/** false = fondos y adornos quietos (ahorra batería). */
val LocalThemeAnimations = compositionLocalOf { true }

private fun argb(a: Int, r: Int, g: Int, b: Int): Int = android.graphics.Color.argb(a, r, g, b)
private fun rgb(r: Int, g: Int, b: Int): Int = android.graphics.Color.rgb(r, g, b)

object AppThemes {

    const val AURORA_ID = "aurora"
    const val SAKURA_ID = "sakura"
    const val ABYSS_ID = "abyss"
    const val SYNTHWAVE_ID = "synthwave"
    const val NEON_STREET_ID = "neon_street"
    const val PURPLE_UNIT_ID = "purple_unit"

    val ALL_IDS = listOf(NEON_STREET_ID, PURPLE_UNIT_ID, AURORA_ID, SYNTHWAVE_ID, ABYSS_ID, SAKURA_ID)

    // ------------------------------------------------------------------------------------------
    // Tipografías
    // ------------------------------------------------------------------------------------------

    private fun typography(
        heading: FontFamily,
        body: FontFamily,
        headingWeight: FontWeight = FontWeight.Bold,
        headingSpacing: Float = 0f,
        bodySpacing: Float = 0f,
        headingStyle: FontStyle = FontStyle.Normal,
        headingShadow: Shadow? = null,
        sizeScale: Float = 1f
    ): Typography {
        fun h(size: Int, line: Int, weight: FontWeight = headingWeight) = TextStyle(
            fontFamily = heading, fontWeight = weight, fontStyle = headingStyle,
            fontSize = (size * sizeScale).sp, lineHeight = (line * sizeScale).sp,
            letterSpacing = headingSpacing.sp, shadow = headingShadow
        )

        fun b(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
            fontFamily = body, fontWeight = weight,
            fontSize = (size * sizeScale).sp, lineHeight = (line * sizeScale).sp,
            letterSpacing = bodySpacing.sp
        )

        return Typography(
            displaySmall = h(34, 40),
            headlineMedium = h(28, 34),
            headlineSmall = h(24, 30),
            titleLarge = h(20, 26),
            titleMedium = h(16, 22, FontWeight.SemiBold),
            titleSmall = h(14, 20, FontWeight.SemiBold),
            bodyLarge = b(16, 24),
            bodyMedium = b(14, 20),
            bodySmall = b(12, 17),
            labelLarge = b(14, 20, FontWeight.SemiBold),
            labelMedium = b(12, 16, FontWeight.SemiBold),
            labelSmall = b(11, 16, FontWeight.Medium)
        )
    }

    private fun roundedShapes(small: Int, medium: Int, large: Int, extra: Int) = Shapes(
        extraSmall = RoundedCornerShape((small / 2).dp),
        small = RoundedCornerShape(small.dp),
        medium = RoundedCornerShape(medium.dp),
        large = RoundedCornerShape(large.dp),
        extraLarge = RoundedCornerShape(extra.dp)
    )

    private fun cutShapes(small: Int, medium: Int, large: Int, extra: Int) = Shapes(
        extraSmall = CutCornerShape(topStart = 0.dp, topEnd = (small / 2).dp, bottomEnd = 0.dp, bottomStart = (small / 2).dp),
        small = CutCornerShape(topStart = 0.dp, topEnd = small.dp, bottomEnd = 0.dp, bottomStart = small.dp),
        medium = CutCornerShape(topStart = 0.dp, topEnd = medium.dp, bottomEnd = 0.dp, bottomStart = medium.dp),
        large = CutCornerShape(topStart = 0.dp, topEnd = large.dp, bottomEnd = 0.dp, bottomStart = large.dp),
        extraLarge = CutCornerShape(topStart = 0.dp, topEnd = extra.dp, bottomEnd = 0.dp, bottomStart = extra.dp)
    )

    // ------------------------------------------------------------------------------------------
    // 1. Neón Callejero (inspirado en la estética ciberpunk: amarillo ácido, cian y rojo sobre negro)
    // ------------------------------------------------------------------------------------------

    private val NeonYellow = Color(0xFFFCEE0A)
    private val NeonCyan = Color(0xFF00F0FF)
    private val NeonRed = Color(0xFFFF2A55)

    private fun neonStreet(): AppThemeSpec {
        val scheme = darkColorScheme(
            primary = NeonYellow, onPrimary = Color(0xFF111100),
            primaryContainer = Color(0xFF3B3700), onPrimaryContainer = Color(0xFFFFF7A0),
            secondary = NeonCyan, onSecondary = Color(0xFF00252A),
            secondaryContainer = Color(0xFF00383F), onSecondaryContainer = Color(0xFF9CF8FF),
            tertiary = NeonRed, onTertiary = Color(0xFF2B0008),
            tertiaryContainer = Color(0xFF5A0A1D), onTertiaryContainer = Color(0xFFFFC9D2),
            background = Color(0xFF07070A), onBackground = Color(0xFFF2F1E4),
            surface = Color(0xFF101014), onSurface = Color(0xFFF2F1E4),
            surfaceVariant = Color(0xFF22222A), onSurfaceVariant = Color(0xFFB4B2A0),
            surfaceContainerLowest = Color(0xFF050507), surfaceContainerLow = Color(0xFF0D0D11),
            surfaceContainer = Color(0xFF14141A), surfaceContainerHigh = Color(0xFF1C1C24),
            surfaceContainerHighest = Color(0xFF262630),
            outline = Color(0xFF8A8420), outlineVariant = Color(0xFF3A3810),
            error = Color(0xFFFF4D4D), onError = Color(0xFF330000)
        )
        return AppThemeSpec(
            id = NEON_STREET_ID,
            displayName = "Neón Callejero",
            tagline = "Amarillo ácido, cian y glitch sobre asfalto negro",
            kind = ThemeKind.NEON_STREET, isDark = true, colorScheme = scheme,
            shapes = cutShapes(8, 12, 16, 22),
            typography = typography(
                heading = FontFamily.Monospace, body = FontFamily.Monospace,
                headingWeight = FontWeight.ExtraBold, headingSpacing = 0.8f, bodySpacing = 0.1f,
                headingShadow = Shadow(NeonYellow.copy(alpha = 0.55f), Offset(0f, 0f), 14f),
                sizeScale = 0.96f
            ),
            cardDecor = CardDecor.BRACKETS,
            accentA = NeonYellow, accentB = NeonCyan, accentC = NeonRed,
            cardAlpha = 0.92f,
            heroShape = CutCornerShape(topStart = 0.dp, topEnd = 22.dp, bottomEnd = 0.dp, bottomStart = 22.dp),
            overlay = OverlayStyle(
                panelBackgroundColorArgb = argb(240, 8, 8, 10),
                panelBorderColorArgb = rgb(252, 238, 10),
                primaryAccentArgb = rgb(252, 238, 10),
                secondaryAccentArgb = rgb(0, 240, 255),
                textPrimaryArgb = rgb(242, 241, 228),
                textSecondaryArgb = rgb(180, 178, 160),
                buttonPrimaryBgArgb = rgb(59, 55, 0),
                buttonSecondaryBgArgb = rgb(24, 24, 30),
                dangerBgArgb = rgb(120, 10, 34),
                cornerRadiusDp = 0f, strokeWidthDp = 2f,
                dashedRoiGuide = false, cutCornersInRoi = true,
                pulseColorArgb = rgb(0, 240, 255)
            ),
            overlayAnimated = true, overlayTypefaceMonospace = true
        )
    }

    // ------------------------------------------------------------------------------------------
    // 2. Unidad Púrpura (inspirado en el mecha clásico: púrpura, verde neón y naranja de alerta)
    // ------------------------------------------------------------------------------------------

    private val UnitPurple = Color(0xFFA56BFF)
    private val UnitGreen = Color(0xFF7CFF3A)
    private val UnitOrange = Color(0xFFFF7A00)

    private fun purpleUnit(): AppThemeSpec {
        val scheme = darkColorScheme(
            primary = UnitPurple, onPrimary = Color(0xFF1C0638),
            primaryContainer = Color(0xFF3A1470), onPrimaryContainer = Color(0xFFEBDDFF),
            secondary = UnitGreen, onSecondary = Color(0xFF0B2600),
            secondaryContainer = Color(0xFF1C4A00), onSecondaryContainer = Color(0xFFC4FFA0),
            tertiary = UnitOrange, onTertiary = Color(0xFF2E1400),
            tertiaryContainer = Color(0xFF6A3200), onTertiaryContainer = Color(0xFFFFDCBF),
            background = Color(0xFF0C0614), onBackground = Color(0xFFEDE6FA),
            surface = Color(0xFF150C22), onSurface = Color(0xFFEDE6FA),
            surfaceVariant = Color(0xFF2A1A48), onSurfaceVariant = Color(0xFFB9A8D8),
            surfaceContainerLowest = Color(0xFF080410), surfaceContainerLow = Color(0xFF110A1D),
            surfaceContainer = Color(0xFF1A0F2B), surfaceContainerHigh = Color(0xFF251540),
            surfaceContainerHighest = Color(0xFF301B52),
            outline = Color(0xFF7A55B8), outlineVariant = Color(0xFF38235E),
            error = Color(0xFFFF5252), onError = Color(0xFF330000)
        )
        return AppThemeSpec(
            id = PURPLE_UNIT_ID,
            displayName = "Unidad Púrpura",
            tagline = "Cabina de mando: hexágonos, radar y alertas naranjas",
            kind = ThemeKind.PURPLE_UNIT, isDark = true, colorScheme = scheme,
            shapes = cutShapes(6, 10, 14, 20),
            typography = typography(
                heading = FontFamily.Monospace, body = FontFamily.SansSerif,
                headingWeight = FontWeight.Bold, headingSpacing = 1.2f,
                headingShadow = Shadow(UnitGreen.copy(alpha = 0.45f), Offset(0f, 0f), 10f),
                sizeScale = 0.97f
            ),
            cardDecor = CardDecor.BRACKETS,
            accentA = UnitPurple, accentB = UnitGreen, accentC = UnitOrange,
            cardAlpha = 0.90f,
            heroShape = CutCornerShape(topStart = 18.dp, topEnd = 0.dp, bottomEnd = 18.dp, bottomStart = 0.dp),
            overlay = OverlayStyle(
                panelBackgroundColorArgb = argb(240, 14, 7, 24),
                panelBorderColorArgb = rgb(165, 107, 255),
                primaryAccentArgb = rgb(124, 255, 58),
                secondaryAccentArgb = rgb(255, 122, 0),
                textPrimaryArgb = rgb(237, 230, 250),
                textSecondaryArgb = rgb(185, 168, 216),
                buttonPrimaryBgArgb = rgb(58, 20, 112),
                buttonSecondaryBgArgb = rgb(30, 18, 52),
                dangerBgArgb = rgb(106, 50, 0),
                cornerRadiusDp = 0f, strokeWidthDp = 2f,
                dashedRoiGuide = true, cutCornersInRoi = true,
                pulseColorArgb = rgb(255, 122, 0)
            ),
            overlayAnimated = true, overlayTypefaceMonospace = true
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3. Aurora (el aspecto original de SubVox; sigue el modo claro/oscuro del sistema)
    // ------------------------------------------------------------------------------------------

    private fun aurora(systemDark: Boolean): AppThemeSpec {
        val scheme = if (systemDark) darkColorScheme(
            primary = Violet400, onPrimary = Color(0xFF1B0F5C),
            primaryContainer = Violet800, onPrimaryContainer = Color(0xFFE4DEFF),
            secondary = Mint400, onSecondary = Color(0xFF00382D),
            secondaryContainer = Color(0xFF005141), onSecondaryContainer = Mint300,
            tertiary = Coral400, onTertiary = Color(0xFF5A0A25),
            tertiaryContainer = Color(0xFF7B2540), onTertiaryContainer = Color(0xFFFFD9E1),
            background = Ink900, onBackground = Color(0xFFE6E7F2),
            surface = Ink850, onSurface = Color(0xFFE6E7F2),
            surfaceVariant = Ink750, onSurfaceVariant = Color(0xFFA9ADC8),
            surfaceContainerLowest = Ink950, surfaceContainerLow = Ink850,
            surfaceContainer = Ink800, surfaceContainerHigh = Ink750, surfaceContainerHighest = Ink700,
            outline = Color(0xFF5A6090), outlineVariant = Color(0xFF2A2F4D),
            error = Color(0xFFFF6B6B), onError = Color(0xFF4A0000)
        ) else lightColorScheme(
            primary = Violet600, onPrimary = Color.White,
            primaryContainer = Color(0xFFE4DEFF), onPrimaryContainer = Color(0xFF1B0F5C),
            secondary = Mint700, onSecondary = Color.White,
            secondaryContainer = Color(0xFFC2F5E8), onSecondaryContainer = Color(0xFF00382D),
            tertiary = Coral600, onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFD9E1), onTertiaryContainer = Color(0xFF5A0A25),
            background = Color(0xFFF6F7FC), onBackground = Color(0xFF14172B),
            surface = Color.White, onSurface = Color(0xFF14172B),
            surfaceVariant = Color(0xFFE8EAF6), onSurfaceVariant = Color(0xFF50567A),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F2FA),
            surfaceContainer = Color(0xFFECEEF8), surfaceContainerHigh = Color(0xFFE4E7F4),
            surfaceContainerHighest = Color(0xFFDADEF0),
            outline = Color(0xFF7A80A8), outlineVariant = Color(0xFFD0D4EA),
            error = Color(0xFFBA1A1A)
        )
        return AppThemeSpec(
            id = AURORA_ID,
            displayName = "Aurora",
            tagline = "El original: violeta, menta y coral flotando",
            kind = ThemeKind.AURORA, isDark = systemDark, colorScheme = scheme,
            shapes = roundedShapes(12, 18, 26, 34),
            typography = SubVoxTypography,
            cardDecor = CardDecor.SOFT,
            accentA = Violet400, accentB = Mint400, accentC = Coral400,
            cardAlpha = 0.93f,
            heroShape = RoundedCornerShape(50),
            overlay = OverlayStyle(
                panelBackgroundColorArgb = argb(238, 14, 16, 32),
                panelBorderColorArgb = argb(150, 157, 140, 255),
                primaryAccentArgb = rgb(157, 140, 255),
                secondaryAccentArgb = rgb(85, 224, 194),
                textPrimaryArgb = rgb(236, 237, 247),
                textSecondaryArgb = rgb(169, 173, 200),
                buttonPrimaryBgArgb = rgb(51, 42, 143),
                buttonSecondaryBgArgb = rgb(30, 35, 66),
                dangerBgArgb = rgb(123, 37, 64),
                cornerRadiusDp = 20f, strokeWidthDp = 1.5f,
                pulseColorArgb = rgb(85, 224, 194)
            ),
            overlayAnimated = false, overlayTypefaceMonospace = false
        )
    }

    // ------------------------------------------------------------------------------------------
    // 4. Atardecer Sintético (synthwave: sol, rejilla en perspectiva, rosa y cian)
    // ------------------------------------------------------------------------------------------

    private val SynthPink = Color(0xFFFF3CAC)
    private val SynthCyan = Color(0xFF2BD9FE)
    private val SynthSun = Color(0xFFFFB86C)

    private fun synthwave(): AppThemeSpec {
        val scheme = darkColorScheme(
            primary = SynthPink, onPrimary = Color(0xFF3A0020),
            primaryContainer = Color(0xFF6B1052), onPrimaryContainer = Color(0xFFFFD3EE),
            secondary = SynthCyan, onSecondary = Color(0xFF00303B),
            secondaryContainer = Color(0xFF00485A), onSecondaryContainer = Color(0xFFB3F0FF),
            tertiary = SynthSun, onTertiary = Color(0xFF3A1E00),
            tertiaryContainer = Color(0xFF6A3A00), onTertiaryContainer = Color(0xFFFFE0BD),
            background = Color(0xFF120B2E), onBackground = Color(0xFFF1E9FF),
            surface = Color(0xFF1A1040), onSurface = Color(0xFFF1E9FF),
            surfaceVariant = Color(0xFF2C1A66), onSurfaceVariant = Color(0xFFC1B0EB),
            surfaceContainerLowest = Color(0xFF0D0724), surfaceContainerLow = Color(0xFF170E3A),
            surfaceContainer = Color(0xFF1F1250), surfaceContainerHigh = Color(0xFF2A1A66),
            surfaceContainerHighest = Color(0xFF36227D),
            outline = Color(0xFF8D6FD6), outlineVariant = Color(0xFF3E2A7E),
            error = Color(0xFFFF6B8B), onError = Color(0xFF3A0010)
        )
        return AppThemeSpec(
            id = SYNTHWAVE_ID,
            displayName = "Atardecer Sintético",
            tagline = "Sol retro, rejilla infinita y neón rosa",
            kind = ThemeKind.SYNTHWAVE, isDark = true, colorScheme = scheme,
            shapes = roundedShapes(10, 16, 22, 30),
            typography = typography(
                heading = FontFamily.SansSerif, body = FontFamily.SansSerif,
                headingWeight = FontWeight.Black, headingSpacing = 0.6f,
                headingStyle = FontStyle.Italic,
                headingShadow = Shadow(SynthPink.copy(alpha = 0.65f), Offset(0f, 0f), 16f)
            ),
            cardDecor = CardDecor.GLOW,
            accentA = SynthPink, accentB = SynthCyan, accentC = SynthSun,
            cardAlpha = 0.88f,
            heroShape = RoundedCornerShape(36.dp),
            overlay = OverlayStyle(
                panelBackgroundColorArgb = argb(236, 22, 12, 56),
                panelBorderColorArgb = rgb(255, 60, 172),
                primaryAccentArgb = rgb(255, 60, 172),
                secondaryAccentArgb = rgb(43, 217, 254),
                textPrimaryArgb = rgb(241, 233, 255),
                textSecondaryArgb = rgb(193, 176, 235),
                buttonPrimaryBgArgb = rgb(107, 16, 82),
                buttonSecondaryBgArgb = rgb(40, 24, 98),
                dangerBgArgb = rgb(138, 30, 62),
                cornerRadiusDp = 16f, strokeWidthDp = 2f,
                pulseColorArgb = rgb(43, 217, 254)
            ),
            overlayAnimated = true, overlayTypefaceMonospace = false
        )
    }

    // ------------------------------------------------------------------------------------------
    // 5. Abismo (océano profundo: rayos de luz, burbujas y bioluminiscencia)
    // ------------------------------------------------------------------------------------------

    private val AbyssAqua = Color(0xFF4DE3F0)
    private val AbyssSea = Color(0xFF5CF2B6)
    private val AbyssCoral = Color(0xFFFF8A7A)

    private fun abyss(): AppThemeSpec {
        val scheme = darkColorScheme(
            primary = AbyssAqua, onPrimary = Color(0xFF00363B),
            primaryContainer = Color(0xFF0B4F5C), onPrimaryContainer = Color(0xFFB8F6FF),
            secondary = AbyssSea, onSecondary = Color(0xFF003827),
            secondaryContainer = Color(0xFF0A5238), onSecondaryContainer = Color(0xFFB9FFE0),
            tertiary = AbyssCoral, onTertiary = Color(0xFF4A0D04),
            tertiaryContainer = Color(0xFF7A2A1E), onTertiaryContainer = Color(0xFFFFDAD3),
            background = Color(0xFF03101C), onBackground = Color(0xFFE0F4FA),
            surface = Color(0xFF071B2C), onSurface = Color(0xFFE0F4FA),
            surfaceVariant = Color(0xFF123B58), onSurfaceVariant = Color(0xFF9CC3D6),
            surfaceContainerLowest = Color(0xFF020B14), surfaceContainerLow = Color(0xFF061625),
            surfaceContainer = Color(0xFF0A2236), surfaceContainerHigh = Color(0xFF0F2E48),
            surfaceContainerHighest = Color(0xFF153B5C),
            outline = Color(0xFF3B7C9C), outlineVariant = Color(0xFF17405B),
            error = Color(0xFFFF7B7B), onError = Color(0xFF3F0000)
        )
        return AppThemeSpec(
            id = ABYSS_ID,
            displayName = "Abismo",
            tagline = "Océano profundo con burbujas y luz que ondea",
            kind = ThemeKind.ABYSS, isDark = true, colorScheme = scheme,
            shapes = roundedShapes(14, 22, 30, 38),
            typography = typography(
                heading = PlusJakartaFamily, body = PlusJakartaFamily,
                headingWeight = FontWeight.ExtraBold, headingSpacing = 0.2f,
                headingShadow = Shadow(AbyssAqua.copy(alpha = 0.5f), Offset(0f, 0f), 14f)
            ),
            cardDecor = CardDecor.SOFT,
            accentA = AbyssAqua, accentB = AbyssSea, accentC = AbyssCoral,
            cardAlpha = 0.86f,
            heroShape = RoundedCornerShape(50),
            overlay = OverlayStyle(
                panelBackgroundColorArgb = argb(236, 5, 22, 38),
                panelBorderColorArgb = argb(190, 77, 227, 240),
                primaryAccentArgb = rgb(77, 227, 240),
                secondaryAccentArgb = rgb(92, 242, 182),
                textPrimaryArgb = rgb(224, 244, 250),
                textSecondaryArgb = rgb(156, 195, 214),
                buttonPrimaryBgArgb = rgb(11, 79, 92),
                buttonSecondaryBgArgb = rgb(15, 46, 72),
                dangerBgArgb = rgb(122, 42, 30),
                cornerRadiusDp = 24f, strokeWidthDp = 1.5f,
                pulseColorArgb = rgb(92, 242, 182)
            ),
            overlayAnimated = true, overlayTypefaceMonospace = false
        )
    }

    // ------------------------------------------------------------------------------------------
    // 6. Sakura (tema claro: pétalos cayendo, tinta y papel)
    // ------------------------------------------------------------------------------------------

    private val SakuraPink = Color(0xFFD6457A)
    private val SakuraMatcha = Color(0xFF5E8C69)
    private val SakuraWisteria = Color(0xFF8E6BBF)

    private fun sakura(): AppThemeSpec {
        val scheme = lightColorScheme(
            primary = SakuraPink, onPrimary = Color.White,
            primaryContainer = Color(0xFFFFD9E6), onPrimaryContainer = Color(0xFF5A0C2C),
            secondary = SakuraMatcha, onSecondary = Color.White,
            secondaryContainer = Color(0xFFD5EBD9), onSecondaryContainer = Color(0xFF0F3A1C),
            tertiary = SakuraWisteria, onTertiary = Color.White,
            tertiaryContainer = Color(0xFFEBDDFF), onTertiaryContainer = Color(0xFF2E1560),
            background = Color(0xFFFFF5F8), onBackground = Color(0xFF3A2A33),
            surface = Color(0xFFFFFFFF), onSurface = Color(0xFF3A2A33),
            surfaceVariant = Color(0xFFF7E3EB), onSurfaceVariant = Color(0xFF745E69),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF0F5),
            surfaceContainer = Color(0xFFFBE8EF), surfaceContainerHigh = Color(0xFFF7DCE6),
            surfaceContainerHighest = Color(0xFFF1CEDB),
            outline = Color(0xFFB98FA3), outlineVariant = Color(0xFFEBCAD7),
            error = Color(0xFFBA1A1A)
        )
        return AppThemeSpec(
            id = SAKURA_ID,
            displayName = "Sakura",
            tagline = "Papel claro, tinta y pétalos que caen",
            kind = ThemeKind.SAKURA, isDark = false, colorScheme = scheme,
            shapes = roundedShapes(10, 16, 22, 30),
            typography = typography(
                heading = FontFamily.Serif, body = FontFamily.Serif,
                headingWeight = FontWeight.Bold, headingSpacing = 0.3f, bodySpacing = 0.1f
            ),
            cardDecor = CardDecor.SOFT,
            accentA = SakuraPink, accentB = SakuraMatcha, accentC = SakuraWisteria,
            cardAlpha = 0.90f,
            heroShape = RoundedCornerShape(50),
            overlay = OverlayStyle(
                panelBackgroundColorArgb = argb(240, 255, 245, 248),
                panelBorderColorArgb = rgb(214, 69, 122),
                primaryAccentArgb = rgb(214, 69, 122),
                secondaryAccentArgb = rgb(94, 140, 105),
                textPrimaryArgb = rgb(58, 42, 51),
                textSecondaryArgb = rgb(116, 94, 105),
                buttonPrimaryBgArgb = rgb(255, 217, 230),
                buttonSecondaryBgArgb = rgb(247, 227, 235),
                dangerBgArgb = rgb(190, 60, 90),
                cornerRadiusDp = 18f, strokeWidthDp = 1.5f,
                pulseColorArgb = rgb(142, 107, 191)
            ),
            overlayAnimated = false, overlayTypefaceMonospace = false
        )
    }

    // ------------------------------------------------------------------------------------------

    fun isKnown(id: String): Boolean = id in ALL_IDS

    fun forId(id: String, systemDark: Boolean): AppThemeSpec = when (id) {
        NEON_STREET_ID -> neonStreet()
        PURPLE_UNIT_ID -> purpleUnit()
        SYNTHWAVE_ID -> synthwave()
        ABYSS_ID -> abyss()
        SAKURA_ID -> sakura()
        else -> aurora(systemDark)
    }

    /** Estilo del panel flotante (View clásica, fuera de Compose). */
    fun overlayStyleFor(id: String): OverlayStyle = forId(id, systemDark = true).overlay

    fun overlayAnimated(id: String): Boolean = forId(id, systemDark = true).overlayAnimated

    fun overlayMonospace(id: String): Boolean = forId(id, systemDark = true).overlayTypefaceMonospace
}
