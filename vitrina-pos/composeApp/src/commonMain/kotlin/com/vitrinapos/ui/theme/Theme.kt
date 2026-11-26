package com.vitrinapos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colores extra que Material no trae: acción de cobrar, estados de stock. */
@Immutable
data class ExtraColors(
    val cta: Color,
    val onCta: Color,
    val warn: Color,
    val warnSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val ok: Color,
    val okSoft: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
)

private val LightExtra = ExtraColors(
    cta = Color(0xFFF26B3A), onCta = Color.White,
    warn = Color(0xFFB27306), warnSoft = Color(0xFFFFF3D9),
    danger = Color(0xFFC2412D), dangerSoft = Color(0xFFFDE8E4),
    ok = Color(0xFF0A5E4E), okSoft = Color(0xFFE0F3ED),
    ink = Color(0xFF13201C), muted = Color(0xFF6B7873), line = Color(0xFFE3E9E6),
)

private val DarkExtra = ExtraColors(
    cta = Color(0xFFFF8A5C), onCta = Color(0xFF2B1006),
    warn = Color(0xFFF2B649), warnSoft = Color(0xFF3A2E14),
    danger = Color(0xFFFF8D7A), dangerSoft = Color(0xFF3C1F1A),
    ok = Color(0xFF6FD8B9), okSoft = Color(0xFF16352D),
    ink = Color(0xFFE6EEEB), muted = Color(0xFF9AA8A3), line = Color(0xFF2A3632),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E7C66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F3ED),
    onPrimaryContainer = Color(0xFF0A5E4E),
    secondary = Color(0xFF0A5E4E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7EDE6),
    onSecondaryContainer = Color(0xFF0A3B31),
    tertiary = Color(0xFFF26B3A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE4D6),
    onTertiaryContainer = Color(0xFF5A1D05),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF13201C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF13201C),
    surfaceVariant = Color(0xFFEEF2F0),
    onSurfaceVariant = Color(0xFF5B6863),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FBFA),
    surfaceContainer = Color(0xFFF2F5F4),
    surfaceContainerHigh = Color(0xFFECF0EE),
    surfaceContainerHighest = Color(0xFFE6EBE9),
    outline = Color(0xFFC9D3CF),
    outlineVariant = Color(0xFFE3E9E6),
    error = Color(0xFFC2412D),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4CC9A8),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF16352D),
    onPrimaryContainer = Color(0xFFA6F0DA),
    secondary = Color(0xFF8FD9C3),
    onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF1E3B34),
    onSecondaryContainer = Color(0xFFCDEDE3),
    tertiary = Color(0xFFFF8A5C),
    onTertiary = Color(0xFF2B1006),
    tertiaryContainer = Color(0xFF4A2414),
    onTertiaryContainer = Color(0xFFFFDBCC),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFE6EEEB),
    surface = Color(0xFF17201D),
    onSurface = Color(0xFFE6EEEB),
    surfaceVariant = Color(0xFF222D29),
    onSurfaceVariant = Color(0xFF9AA8A3),
    surfaceContainerLowest = Color(0xFF0B110F),
    surfaceContainerLow = Color(0xFF141C19),
    surfaceContainer = Color(0xFF1A2320),
    surfaceContainerHigh = Color(0xFF212B28),
    surfaceContainerHighest = Color(0xFF2A3632),
    outline = Color(0xFF3A4843),
    outlineVariant = Color(0xFF2A3632),
    error = Color(0xFFFF8D7A),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

object AppTheme {
    val extra: ExtraColors @Composable get() = LocalExtraColors.current
}

private val base = Typography()
private val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
)

val MoneyStyle = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun VitrinaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Paleta para categorías y para el fondo de productos sin foto. */
// CategoryPalette está en el núcleo (core/ui/theme/Palette.kt): la usan también los datos de ejemplo.
