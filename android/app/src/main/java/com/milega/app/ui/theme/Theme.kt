package com.milega.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object BrandColors {
    val Green = Color(0xFF0B6E4F)
    val Marigold = Color(0xFFF4A300)
    val Indigo = Color(0xFF3D4ED8)
    val Rose = Color(0xFFC2416B)
    val Teal = Color(0xFF00838F)

    // Availability / confidence (paired with text labels, never colour alone)
    val Good = Color(0xFF1E8E5A)
    val Okay = Color(0xFF2F7FA8)
    val Warn = Color(0xFFB7791F)
    val Bad = Color(0xFFB3261E)
    val Neutral = Color(0xFF7A8580)
}

private val LightColors = lightColorScheme(
    primary = BrandColors.Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EFE3),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6B8),
    onSecondaryContainer = Color(0xFF2B1A00),
    tertiary = BrandColors.Indigo,
    tertiaryContainer = Color(0xFFDEE1FF),
    onTertiaryContainer = Color(0xFF00105C),
    background = Color(0xFFF6F7F4),
    onBackground = Color(0xFF18211D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18211D),
    surfaceVariant = Color(0xFFEEF3EF),
    onSurfaceVariant = Color(0xFF4F5B55),
    surfaceContainer = Color(0xFFF1F4F1),
    surfaceContainerHigh = Color(0xFFEAEFEB),
    outline = Color(0xFFC2CBC5),
    outlineVariant = Color(0xFFDDE3DF),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FD3AA),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513A),
    onPrimaryContainer = Color(0xFFB7F0D6),
    secondary = Color(0xFFFFC266),
    onSecondary = Color(0xFF462B00),
    secondaryContainer = Color(0xFF5A3D00),
    onSecondaryContainer = Color(0xFFFFE6B8),
    tertiary = Color(0xFFB9C3FF),
    tertiaryContainer = Color(0xFF253A9E),
    onTertiaryContainer = Color(0xFFDEE1FF),
    background = Color(0xFF111513),
    onBackground = Color(0xFFE2E8E4),
    surface = Color(0xFF1A201D),
    onSurface = Color(0xFFE2E8E4),
    surfaceVariant = Color(0xFF2B332F),
    onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceContainer = Color(0xFF1E2421),
    surfaceContainerHigh = Color(0xFF262D29),
    outline = Color(0xFF59635E),
    outlineVariant = Color(0xFF3A433F),
    error = Color(0xFFFFB4AB),
)

private val BaseTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

/** Shopkeeper screens use larger, bolder type for quick reading at the counter. */
private fun Typography.scaled(factor: Float): Typography {
    fun TextStyle.s() = copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)
    return Typography(
        displaySmall = displaySmall.s(), headlineMedium = headlineMedium.s(), headlineSmall = headlineSmall.s(),
        titleLarge = titleLarge.s(), titleMedium = titleMedium.s(), titleSmall = titleSmall.s(),
        bodyLarge = bodyLarge.s(), bodyMedium = bodyMedium.s(), bodySmall = bodySmall.s(),
        labelLarge = labelLarge.s(), labelMedium = labelMedium.s(), labelSmall = labelSmall.s(),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MilegaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = BaseTypography,
        shapes = AppShapes,
        content = content,
    )
}

@Composable
fun ShopkeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = BaseTypography.scaled(1.1f),
        shapes = AppShapes,
        content = content,
    )
}
