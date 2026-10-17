package com.rafeeq.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Subtle green header wash used by the top app bar. */
private val LightHeaderGradient = listOf(Color(0xFF0E7C50), Color(0xFF15A06B))
private val DarkHeaderGradient = listOf(Color(0xFF0B5C3A), Color(0xFF0E7C50))

/**
 * Mirrors the [darkTheme] flag actually applied by [RafeeqTheme], so nested composables can
 * react to the *chosen* theme instead of only the system one (the user may force Light/Dark).
 */
internal val LocalDarkTheme = staticCompositionLocalOf { false }

private val RafeeqLightColors = lightColorScheme(
    primary = Color(0xFF0E7C50),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA9F2CA),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6355),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E8D7),
    onSecondaryContainer = Color(0xFF0B1F13),
    background = Color(0xFFF6FBF4),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF6FBF4),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF414942),
    outline = Color(0xFF717972)
)

private val RafeeqDarkColors = darkColorScheme(
    primary = Color(0xFF8ED6AE),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF005232),
    onPrimaryContainer = Color(0xFFA9F2CA),
    secondary = Color(0xFFB4CCBB),
    onSecondary = Color(0xFF203528),
    secondaryContainer = Color(0xFF364B3E),
    onSecondaryContainer = Color(0xFFD0E8D7),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDEE4DE),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDEE4DE),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC1C9C1),
    outline = Color(0xFF8B938B)
)

/** Rounded 24.dp corners across the app, per the v0.4 design brief. */
private val RafeeqShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RafeeqTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) RafeeqDarkColors else RafeeqLightColors,
            shapes = RafeeqShapes,
            content = content
        )
    }
}

@Composable
fun rafeeqHeaderGradient(): Brush =
    Brush.horizontalGradient(
        if (LocalDarkTheme.current) DarkHeaderGradient else LightHeaderGradient
    )
