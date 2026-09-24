package com.example.smartroomdashboard.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Paper = Color(0xFFFFFFFF)
private val Ink = Color(0xFF000000)
private val MutedInk = Color(0xFF222222)

private val EinkColors = lightColorScheme(
    background = Paper,
    surface = Paper,
    surfaceVariant = Paper,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Ink,
    // Never fill a control black: black-on-black is unreadable on Boox's
    // grayscale display and some firmware applies an additional inversion.
    primary = Paper,
    onPrimary = Ink,
    secondary = Paper,
    onSecondary = Ink,
    outline = Ink,
    error = Ink,
    onError = Paper,
)

private val EinkType = Typography(
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink),
    bodyLarge = TextStyle(fontSize = 18.sp, color = Ink),
    bodyMedium = TextStyle(fontSize = 16.sp, color = Ink),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EinkColors, typography = EinkType) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides null,
            LocalMinimumInteractiveComponentSize provides 56.dp,
        ) {
            content()
        }
    }
}
