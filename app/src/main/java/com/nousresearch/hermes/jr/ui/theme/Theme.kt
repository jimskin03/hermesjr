package com.nousresearch.hermes.jr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Field = Color(0xFF0D1117)
val Elevated = Color(0xFF161B22)
val Accent = Color(0xFFC08532)
val Danger = Color(0xFFCF2D56)
val Ink = Color(0xFFFFFFFF)
val Muted = Color(0xFF8B949E)

private val DarkColors = darkColorScheme(
    background = Field,
    surface = Field,
    surfaceContainer = Elevated,
    surfaceContainerHigh = Elevated,
    primary = Accent,
    onPrimary = Field,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Muted,
    error = Danger,
    onError = Ink,
)

@Composable
fun HermesJrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
