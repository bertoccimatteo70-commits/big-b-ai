package com.bigbai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigbai.core.model.ThemeMode

/**
 * BIG B AI visual identity: deep indigo for actions, cyan for live/AI states,
 * a near-white canvas in light mode and a navy canvas in dark mode. Colours are
 * intentionally few and reused through Material roles so every screen stays
 * coherent without per-screen hex values.
 */
object BigBBrand {
    val Indigo = Color(0xFF3B5BFF)
    val IndigoDeep = Color(0xFF1B2A6B)
    val Cyan = Color(0xFF12B8C7)
    val Success = Color(0xFF16A34A)
    val Warning = Color(0xFFD97706)
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F4BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E8FF),
    onPrimaryContainer = Color(0xFF0A1744),
    secondary = Color(0xFF0E7C8C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F0F4),
    onSecondaryContainer = Color(0xFF06343B),
    tertiary = Color(0xFF6D28D9),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE4FF),
    onTertiaryContainer = Color(0xFF2A1065),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEDF0F7),
    onSurfaceVariant = Color(0xFF4A5568),
    outline = Color(0xFFC3CAD9),
    outlineVariant = Color(0xFFE2E7F0),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF5F1414),
    scrim = Color(0xFF0A0F1C),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA8B8FF),
    onPrimary = Color(0xFF0A1744),
    primaryContainer = Color(0xFF22306B),
    onPrimaryContainer = Color(0xFFE0E6FF),
    secondary = Color(0xFF7FE3EC),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF0B4A52),
    onSecondaryContainer = Color(0xFFD3F0F4),
    tertiary = Color(0xFFC7B2FF),
    onTertiary = Color(0xFF2A1065),
    tertiaryContainer = Color(0xFF3A2A66),
    onTertiaryContainer = Color(0xFFEDE4FF),
    background = Color(0xFF0A0F1C),
    onBackground = Color(0xFFE6EAF4),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE6EAF4),
    surfaceVariant = Color(0xFF1A2334),
    onSurfaceVariant = Color(0xFFA5B0C4),
    outline = Color(0xFF3A4557),
    outlineVariant = Color(0xFF263144),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF5F1414),
    errorContainer = Color(0xFF5F1414),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

private val BigBAiTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

private val BigBAiShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun BigBAiTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = BigBAiTypography,
        shapes = BigBAiShapes,
        content = content,
    )
}
