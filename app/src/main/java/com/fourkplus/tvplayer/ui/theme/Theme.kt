package com.fourkplus.tvplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF06101F)
val DeepBlue = Color(0xFF0A2B55)
val BrandBlue = Color(0xFF1499F5)
val Cyan = Color(0xFF23D7EE)
val Orange = Color(0xFFFFB547)
val Ice = Color(0xFFF4F8FF)

private val DarkColors = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0C4273),
    secondary = Cyan,
    onSecondary = Color(0xFF001F26),
    secondaryContainer = Color(0xFF073D49),
    tertiary = Orange,
    background = Navy,
    onBackground = Color(0xFFF4F8FF),
    surface = Color(0xFF0A1B31),
    onSurface = Color(0xFFF4F8FF),
    surfaceVariant = Color(0xFF12304E),
    onSurfaceVariant = Color(0xFFB7CAE2)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF087BD4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EDFF),
    secondary = Color(0xFF008D9F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F5F5),
    tertiary = Color(0xFFB66D00),
    background = Ice,
    onBackground = Color(0xFF0B1A33),
    surface = Color(0xFFFCFEFF),
    onSurface = Color(0xFF0B1A33),
    surfaceVariant = Color(0xFFE1F0FF),
    onSurfaceVariant = Color(0xFF52657D)
)

@Composable
fun FourKPlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
