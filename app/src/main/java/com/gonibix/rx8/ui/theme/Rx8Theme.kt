package com.gonibix.rx8.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CanvasColor = Color(0xFF0C1117)
val CardColor = Color(0xFF171E27)
val RaisedColor = Color(0xFF202A35)
val AccentColor = Color(0xFFFF9877)
val WarmWhite = Color(0xFFF7F2EA)
val MutedColor = Color(0xFFA7B2BF)
val BorderColor = Color(0xFF2C3743)
val MintColor = Color(0xFFB1DBC8)

private val DashboardColors = darkColorScheme(
    primary = AccentColor,
    onPrimary = Color(0xFF32170F),
    primaryContainer = Color(0xFF452D25),
    onPrimaryContainer = WarmWhite,
    secondary = MintColor,
    onSecondary = CanvasColor,
    background = CanvasColor,
    onBackground = WarmWhite,
    surface = CardColor,
    onSurface = WarmWhite,
    surfaceVariant = RaisedColor,
    onSurfaceVariant = MutedColor,
    outline = BorderColor,
    error = AccentColor,
    onError = CanvasColor,
)

@Composable
fun Rx8Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DashboardColors,
        typography = Typography(),
        content = content,
    )
}
