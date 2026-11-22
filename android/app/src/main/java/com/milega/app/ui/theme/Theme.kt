package com.milega.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.milega.app.R

// ------------------------------------------------------------------ palette

/** Raw brand palette. UI code should prefer [MaterialTheme.colorScheme] or [MilegaColors]. */
object Palette {
    val Forest = Color(0xFF103B30)
    val Green = Color(0xFF155642)
    val GreenBright = Color(0xFF1F7A5C)
    val Cream = Color(0xFFFBF8F1)
    val CreamDeep = Color(0xFFF3EEE2)
    val Terracotta = Color(0xFFEF7349)
    val TerracottaDeep = Color(0xFFC4532B)
    val Mint = Color(0xFFEAF3EC)
    val MintDeep = Color(0xFFD5E8DA)
    val Ink = Color(0xFF15241E)
    val InkSoft = Color(0xFF4C5E56)
    val Line = Color(0xFFE2DDD0)
    val White = Color(0xFFFFFFFF)

    // Night
    val NightBg = Color(0xFF0D1613)
    val NightSurface = Color(0xFF15211C)
    val NightSurfaceHigh = Color(0xFF1C2B25)
    val NightLine = Color(0xFF2C3D35)
    val NightText = Color(0xFFE7EFEA)
    val NightTextSoft = Color(0xFFA9BDB3)
    val NightGreen = Color(0xFF7FD6AE)
    val NightTerracotta = Color(0xFFFF9A76)
}

/** Semantic colours that Material's scheme doesn't name. Always paired with text labels. */
@Immutable
data class MilegaColors(
    val success: Color,
    val info: Color,
    val warning: Color,
    val danger: Color,
    val neutral: Color,
    val accent: Color,
    val onAccent: Color,
    val brandSurface: Color,
    val onBrandSurface: Color,
    val google: Color,
    val demo: Color,
    val skeleton: Color,
    val line: Color,
)

private val LightExtras = MilegaColors(
    success = Color(0xFF1C7C50), info = Color(0xFF2A6F94), warning = Color(0xFF9A6410),
    danger = Color(0xFFB3261E), neutral = Color(0xFF6B7A73), accent = Palette.Terracotta,
    onAccent = Palette.White, brandSurface = Palette.Forest, onBrandSurface = Palette.Cream,
    google = Color(0xFF4F5B66), demo = Color(0xFF4B4FC4), skeleton = Color(0xFFEAE5D9), line = Palette.Line,
)

private val DarkExtras = MilegaColors(
    success = Color(0xFF6FD3A0), info = Color(0xFF83C3E6), warning = Color(0xFFF0C062),
    danger = Color(0xFFFFB4AB), neutral = Color(0xFFA0AFA8), accent = Palette.NightTerracotta,
    onAccent = Color(0xFF3A1406), brandSurface = Color(0xFF123026), onBrandSurface = Palette.NightText,
    google = Color(0xFFB7C3CC), demo = Color(0xFFB9BCFF), skeleton = Color(0xFF22322B), line = Palette.NightLine,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Palette.Green,
    onPrimary = Palette.White,
    primaryContainer = Palette.Mint,
    onPrimaryContainer = Palette.Forest,
    secondary = Palette.TerracottaDeep,
    onSecondary = Palette.White,
    secondaryContainer = Color(0xFFFDE3D8),
    onSecondaryContainer = Color(0xFF4A1A08),
    tertiary = Color(0xFF3F4FB8),
    tertiaryContainer = Color(0xFFE2E5FF),
    onTertiaryContainer = Color(0xFF10175C),
    background = Palette.Cream,
    onBackground = Palette.Ink,
    surface = Palette.White,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Mint,
    onSurfaceVariant = Palette.InkSoft,
    surfaceContainerLowest = Palette.White,
    surfaceContainerLow = Color(0xFFFDFBF6),
    surfaceContainer = Palette.CreamDeep,
    surfaceContainerHigh = Color(0xFFEDE7D9),
    surfaceContainerHighest = Color(0xFFE6DFCF),
    outline = Color(0xFFC9C2B2),
    outlineVariant = Palette.Line,
    error = Color(0xFFB3261E),
    inverseSurface = Palette.Forest,
    inverseOnSurface = Palette.Cream,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Palette.NightGreen,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF17473A),
    onPrimaryContainer = Color(0xFFBDEFD7),
    secondary = Palette.NightTerracotta,
    onSecondary = Color(0xFF3A1406),
    secondaryContainer = Color(0xFF5B2A17),
    onSecondaryContainer = Color(0xFFFFDBCD),
    tertiary = Color(0xFFBAC3FF),
    tertiaryContainer = Color(0xFF2A3790),
    onTertiaryContainer = Color(0xFFDFE2FF),
    background = Palette.NightBg,
    onBackground = Palette.NightText,
    surface = Palette.NightSurface,
    onSurface = Palette.NightText,
    surfaceVariant = Palette.NightSurfaceHigh,
    onSurfaceVariant = Palette.NightTextSoft,
    surfaceContainerLowest = Color(0xFF0A120F),
    surfaceContainerLow = Color(0xFF121D18),
    surfaceContainer = Palette.NightSurface,
    surfaceContainerHigh = Palette.NightSurfaceHigh,
    surfaceContainerHighest = Color(0xFF243630),
    outline = Color(0xFF4A5E55),
    outlineVariant = Palette.NightLine,
    error = Color(0xFFFFB4AB),
    inverseSurface = Palette.NightText,
    inverseOnSurface = Palette.NightBg,
)

// ---------------------------------------------------------------- typography

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Manrope (OFL) for Latin; Kannada and Devanagari fall back to the system Noto fonts. */
val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Manrope, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.em,
)

private val BaseTypography = Typography(
    displayMedium = style(36, 42, FontWeight.ExtraBold, -0.025),
    displaySmall = style(30, 36, FontWeight.ExtraBold, -0.02),
    headlineLarge = style(28, 34, FontWeight.Bold, -0.015),
    headlineMedium = style(24, 30, FontWeight.Bold, -0.01),
    headlineSmall = style(20, 26, FontWeight.Bold, -0.005),
    titleLarge = style(18, 24, FontWeight.Bold),
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Medium),
    labelLarge = style(15, 20, FontWeight.Bold, 0.005),
    labelMedium = style(13, 16, FontWeight.SemiBold, 0.01),
    labelSmall = style(11, 14, FontWeight.SemiBold, 0.02),
)

/** Shopkeeper screens use larger, bolder type for quick reading at the counter. */
private fun Typography.scaled(factor: Float): Typography {
    fun TextStyle.s() = copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)
    return Typography(
        displayMedium = displayMedium.s(), displaySmall = displaySmall.s(), headlineLarge = headlineLarge.s(),
        headlineMedium = headlineMedium.s(), headlineSmall = headlineSmall.s(),
        titleLarge = titleLarge.s(), titleMedium = titleMedium.s(), titleSmall = titleSmall.s(),
        bodyLarge = bodyLarge.s(), bodyMedium = bodyMedium.s(), bodySmall = bodySmall.s(),
        labelLarge = labelLarge.s(), labelMedium = labelMedium.s(), labelSmall = labelSmall.s(),
    )
}

// --------------------------------------------------------- spacing & shapes

/** 4-point spacing scale. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    /** Horizontal page gutter. */
    val gutter = 20.dp
    /** Minimum touch target (accessibility). */
    val touch = 48.dp
}

object Radius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 22.dp
    val xl = 28.dp
}

object Elevation {
    val flat = 0.dp
    val card = 1.dp
    val raised = 3.dp
    val floating = 8.dp
}

/** Motion: short, purposeful. Respect system "remove animations" by keeping durations small. */
object Motion {
    const val fast = 140
    const val medium = 240
    const val slow = 360
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standard = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

val LocalMilegaColors = staticCompositionLocalOf { LightExtras }

/** Brand colours beyond Material's scheme: `Milega.colors.success`, `.google`, `.accent`… */
object Milega {
    val colors: MilegaColors
        @Composable get() = LocalMilegaColors.current
}

@Composable
fun MilegaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMilegaColors provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = BaseTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

@Composable
fun ShopkeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = BaseTypography.scaled(1.08f),
        shapes = AppShapes,
        content = content,
    )
}

/** Backwards-compatible names used by existing seller screens. */
object BrandColors {
    val Green = Palette.Green
    val Marigold = Palette.Terracotta
    val Indigo = Color(0xFF4B4FC4)
    val Rose = Color(0xFFB4436C)
    val Teal = Color(0xFF00838F)
    val Good = Color(0xFF1C7C50)
    val Okay = Color(0xFF2A6F94)
    val Warn = Color(0xFF9A6410)
    val Bad = Color(0xFFB3261E)
    val Neutral = Color(0xFF6B7A73)
}
