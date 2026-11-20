package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.domain.AppVisualStyle

/**
 * Tokens semánticos extendidos del diseño actual para soportar estilos globales
 * (Resonancia Oscura, OLED Puro, Púrpura Synthwave y Claro Cristal) de forma coherente.
 */
data class AppStyleTokens(
    val style: AppVisualStyle,
    val isDark: Boolean,
    val canvasBackground: Color,
    val cardSurface: Color,
    val cardSelectedSurface: Color,
    val panelSurface: Color,
    val navSurface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val borderSubtle: Color,
    val borderBright: Color,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val accentTertiary: Color,
    val actionPrimaryBg: Color,
    val actionPrimaryText: Color,
    val actionSecondaryBg: Color,
    val actionSecondaryText: Color,
    val successAccent: Color,
    val warningAccent: Color,
    val highwayBackground: Color
)

val LocalAppStyleTokens = staticCompositionLocalOf {
    tokensForStyle(AppVisualStyle.NEON_VOID)
}

fun tokensForStyle(style: AppVisualStyle): AppStyleTokens = when (style) {
    AppVisualStyle.NEON_VOID -> AppStyleTokens(
        style = style,
        isDark = true,
        canvasBackground = VoidBackground,
        cardSurface = SurfaceContainer,
        cardSelectedSurface = SurfaceContainerHigh,
        panelSurface = SurfaceContainerLow,
        navSurface = SurfaceContainerLow,
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFFB8C0D4),
        borderSubtle = GlassWhite12,
        borderBright = GlassWhite20,
        accentPrimary = PrimaryVioletLight,
        accentSecondary = SecondaryCyanLight,
        accentTertiary = Color(0xFFFB7185),
        actionPrimaryBg = Color(0xFF7C3AED),
        actionPrimaryText = Color.White,
        actionSecondaryBg = Color(0xFF0891B2),
        actionSecondaryText = Color.White,
        successAccent = Color(0xFF34D399),
        warningAccent = Color(0xFFFBBF24),
        highwayBackground = VoidBackground
    )
    AppVisualStyle.OLED_PURE -> AppStyleTokens(
        style = style,
        isDark = true,
        canvasBackground = Color(0xFF000000),
        cardSurface = Color(0xFF0C0E14),
        cardSelectedSurface = Color(0xFF161A26),
        panelSurface = Color(0xFF07080C),
        navSurface = Color(0xFF000000),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFB4BED2),
        borderSubtle = Color(0x2EFFFFFF),
        borderBright = Color(0x52FFFFFF),
        accentPrimary = PrimaryVioletLight,
        accentSecondary = SecondaryCyanLight,
        accentTertiary = Color(0xFFFB7185),
        actionPrimaryBg = Color(0xFF7C3AED),
        actionPrimaryText = Color.White,
        actionSecondaryBg = Color(0xFF0891B2),
        actionSecondaryText = Color.White,
        successAccent = Color(0xFF34D399),
        warningAccent = Color(0xFFFBBF24),
        highwayBackground = Color(0xFF000000)
    )
    AppVisualStyle.SYNTHWAVE_DUSK -> AppStyleTokens(
        style = style,
        isDark = true,
        canvasBackground = Color(0xFF120824),
        cardSurface = Color(0xFF1E1038),
        cardSelectedSurface = Color(0xFF2B1850),
        panelSurface = Color(0xFF170C2E),
        navSurface = Color(0xFF140929),
        textPrimary = Color(0xFFFDF4FF),
        textSecondary = Color(0xFFD8B4FE),
        borderSubtle = Color(0x38E879F9),
        borderBright = Color(0x66F472B6),
        accentPrimary = Color(0xFFE879F9),
        accentSecondary = Color(0xFF38BDF8),
        accentTertiary = Color(0xFFFF5C93),
        actionPrimaryBg = Color(0xFF9333EA),
        actionPrimaryText = Color.White,
        actionSecondaryBg = Color(0xFF0284C7),
        actionSecondaryText = Color.White,
        successAccent = Color(0xFF34D399),
        warningAccent = Color(0xFFFBBF24),
        highwayBackground = Color(0xFF0D051B)
    )
    AppVisualStyle.FROST_LIGHT -> AppStyleTokens(
        style = style,
        isDark = false,
        canvasBackground = Color(0xFFF3F6FC),
        cardSurface = Color(0xFFFFFFFF),
        cardSelectedSurface = Color(0xFFEAEFFF),
        panelSurface = Color(0xFFFAFBFD),
        navSurface = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF0F172A),
        textSecondary = Color(0xFF475569),
        borderSubtle = Color(0x241E293B),
        borderBright = Color(0x476D28D9),
        accentPrimary = Color(0xFF6D28D9),
        accentSecondary = Color(0xFF0369A1),
        accentTertiary = Color(0xFFBE123C),
        actionPrimaryBg = Color(0xFF6D28D9),
        actionPrimaryText = Color.White,
        actionSecondaryBg = Color(0xFF0369A1),
        actionSecondaryText = Color.White,
        successAccent = Color(0xFF047857),
        warningAccent = Color(0xFFB45309),
        highwayBackground = Color(0xFF0E1322)
    )
}

fun colorSchemeForStyle(style: AppVisualStyle): ColorScheme {
    val tokens = tokensForStyle(style)
    return if (tokens.isDark) {
        darkColorScheme(
            primary = tokens.accentPrimary,
            onPrimary = OnPrimaryDark,
            primaryContainer = PrimaryViolet,
            onPrimaryContainer = Color.White,
            secondary = tokens.accentSecondary,
            onSecondary = OnSecondaryDark,
            secondaryContainer = SecondaryCyan,
            onSecondaryContainer = Color.White,
            tertiary = TertiaryMagentaLight,
            onTertiary = SurfaceDark,
            tertiaryContainer = tokens.accentTertiary,
            onTertiaryContainer = Color.White,
            background = tokens.canvasBackground,
            onBackground = tokens.textPrimary,
            surface = tokens.canvasBackground,
            onSurface = tokens.textPrimary,
            surfaceVariant = tokens.cardSelectedSurface,
            onSurfaceVariant = tokens.textSecondary,
            surfaceContainerLowest = tokens.canvasBackground,
            surfaceContainerLow = tokens.panelSurface,
            surfaceContainer = tokens.cardSurface,
            surfaceContainerHigh = tokens.cardSelectedSurface,
            surfaceContainerHighest = SurfaceContainerHighest,
            outline = OutlineSubtle,
            outlineVariant = tokens.borderSubtle,
            error = ErrorCoral
        )
    } else {
        lightColorScheme(
            primary = tokens.accentPrimary,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEDE9FE),
            onPrimaryContainer = Color(0xFF3B0764),
            secondary = tokens.accentSecondary,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE0F2FE),
            onSecondaryContainer = Color(0xFF0C4A6E),
            tertiary = tokens.accentTertiary,
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFE4E6),
            onTertiaryContainer = Color(0xFF881337),
            background = tokens.canvasBackground,
            onBackground = tokens.textPrimary,
            surface = tokens.canvasBackground,
            onSurface = tokens.textPrimary,
            surfaceVariant = tokens.cardSelectedSurface,
            onSurfaceVariant = tokens.textSecondary,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = tokens.panelSurface,
            surfaceContainer = tokens.cardSurface,
            surfaceContainerHigh = tokens.cardSelectedSurface,
            surfaceContainerHighest = Color(0xFFE2E8F0),
            outline = Color(0xFF94A3B8),
            outlineVariant = tokens.borderSubtle,
            error = Color(0xFFDC2626)
        )
    }
}

val NeonShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MyApplicationTheme(
    visualStyle: AppVisualStyle = AppVisualStyle.NEON_VOID,
    content: @Composable () -> Unit
) {
    val tokens = tokensForStyle(visualStyle)
    val colorScheme = colorSchemeForStyle(visualStyle)
    CompositionLocalProvider(LocalAppStyleTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = NeonShapes,
            content = content
        )
    }
}
