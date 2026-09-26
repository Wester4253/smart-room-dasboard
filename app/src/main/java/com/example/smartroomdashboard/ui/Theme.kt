package com.example.smartroomdashboard.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartroomdashboard.domain.DEFAULT_TEXT_SCALE

/**
 * A real greyscale ramp.
 *
 * The panel is 16-level greyscale, so the old pure-black/pure-white-only scheme
 * wasted most of the available contrast steps. Every role below is a distinct
 * level, which gives borders, fills and secondary text something to sit between.
 */
object Eink {
    val Paper = Color(0xFFFFFFFF)
    val Mist = Color(0xFFEFEFEF)
    val Parchment = Color(0xFFDBDBDB)
    val Slate = Color(0xFFB4B4B4)
    val Ash = Color(0xFF8A8A8A)
    val Graphite = Color(0xFF5A5A5A)
    val Onyx = Color(0xFF2E2E2E)
    val Ink = Color(0xFF000000)
}

private val EinkColors = lightColorScheme(
    background = Eink.Paper,
    surface = Eink.Paper,
    surfaceVariant = Eink.Mist,
    onBackground = Eink.Ink,
    onSurface = Eink.Ink,
    onSurfaceVariant = Eink.Onyx,
    // Never fill a control black: black-on-black is unreadable on Boox's
    // grayscale display and some firmware applies an additional inversion.
    primary = Eink.Paper,
    onPrimary = Eink.Ink,
    primaryContainer = Eink.Parchment,
    onPrimaryContainer = Eink.Ink,
    inversePrimary = Eink.Onyx,
    secondary = Eink.Paper,
    onSecondary = Eink.Ink,
    secondaryContainer = Eink.Mist,
    onSecondaryContainer = Eink.Ink,
    tertiary = Eink.Paper,
    onTertiary = Eink.Ink,
    tertiaryContainer = Eink.Mist,
    onTertiaryContainer = Eink.Ink,
    // Material 3's tonal surface roles otherwise fall back to a tinted baseline
    // palette, which would reintroduce colour the panel cannot show.
    surfaceBright = Eink.Paper,
    surfaceDim = Eink.Mist,
    surfaceContainerLowest = Eink.Paper,
    surfaceContainerLow = Eink.Paper,
    surfaceContainer = Eink.Mist,
    surfaceContainerHigh = Eink.Mist,
    surfaceContainerHighest = Eink.Parchment,
    surfaceTint = Eink.Paper,
    inverseSurface = Eink.Onyx,
    inverseOnSurface = Eink.Paper,
    outline = Eink.Onyx,
    outlineVariant = Eink.Ash,
    scrim = Eink.Ink,
    error = Eink.Ink,
    onError = Eink.Paper,
    errorContainer = Eink.Parchment,
    onErrorContainer = Eink.Ink,
)

/**
 * Every size is multiplied by [textScale] through [scaledTypography] rather than
 * left to the user's system font setting. E-ink legibility at arm's length is a
 * different problem from a phone, and the panel's refresh cost rises sharply with
 * glyph coverage, so density is a poor proxy here.
 */
private val BaseType = Typography(
    displayLarge = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    // Material's 12sp default is far too small on a 10.3" panel.
    bodySmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

private val EinkShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

/** The active multiplier, so non-`Text` call sites can size themselves too. */
val LocalTextScale = staticCompositionLocalOf { DEFAULT_TEXT_SCALE }

private fun TextStyle.scaled(factor: Float): TextStyle =
    copy(
        fontSize = fontSize * factor,
        lineHeight = if (lineHeight == TextUnit.Unspecified) lineHeight else lineHeight * factor,
    )

internal fun scaledTypography(factor: Float): Typography {
    fun TextStyle.s() = scaled(factor)
    return Typography(
        displayLarge = BaseType.displayLarge.s(),
        displayMedium = BaseType.displayMedium.s(),
        displaySmall = BaseType.displaySmall.s(),
        headlineLarge = BaseType.headlineLarge.s(),
        headlineMedium = BaseType.headlineMedium.s(),
        headlineSmall = BaseType.headlineSmall.s(),
        titleLarge = BaseType.titleLarge.s(),
        titleMedium = BaseType.titleMedium.s(),
        titleSmall = BaseType.titleSmall.s(),
        bodyLarge = BaseType.bodyLarge.s(),
        bodyMedium = BaseType.bodyMedium.s(),
        bodySmall = BaseType.bodySmall.s(),
        labelLarge = BaseType.labelLarge.s(),
        labelMedium = BaseType.labelMedium.s(),
        labelSmall = BaseType.labelSmall.s(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRoomTheme(
    textScale: Float = DEFAULT_TEXT_SCALE,
    content: @Composable () -> Unit,
) {
    val factor = textScale.coerceIn(0.85f, 1.75f)
    MaterialTheme(
        colorScheme = EinkColors,
        typography = scaledTypography(factor),
        shapes = EinkShapes,
    ) {
        CompositionLocalProvider(
            // Ripples animate a whole surface, which on a 16-level panel reads as
            // a full-screen flash. Feedback is drawn explicitly instead, see
            // `EinkButton`.
            LocalRippleConfiguration provides null,
            LocalMinimumInteractiveComponentSize provides 56.dp,
            LocalTextScale provides factor,
        ) {
            content()
        }
    }
}
