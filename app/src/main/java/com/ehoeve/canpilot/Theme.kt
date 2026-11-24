package com.ehoeve.canpilot

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Always dark: the app is used in a car, and the plots and bit grid are drawn for a dark background. */
private val Colors = darkColorScheme(
    primary = Color(0xFF4FC3F7), onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004D6B), onPrimaryContainer = Color(0xFFC3E8FF),
    secondary = Color(0xFFB5C9D7), onSecondary = Color(0xFF20333F),
    secondaryContainer = Color(0xFF263742), onSecondaryContainer = Color(0xFFD1E5F3),
    tertiary = Color(0xFF81C784), onTertiary = Color(0xFF00390F),
    tertiaryContainer = Color(0xFF1B4D24), onTertiaryContainer = Color(0xFFB6F2B8),
    error = Color(0xFFFF8A80), onError = Color(0xFF5F0A0A),
    background = Color(0xFF0E1114), onBackground = Color(0xFFE3E6E9),
    surface = Color(0xFF0E1114), onSurface = Color(0xFFE3E6E9),
    surfaceVariant = Color(0xFF2A3036), onSurfaceVariant = Color(0xFF9BA6B0),
    surfaceContainerLowest = Color(0xFF0A0D0F), surfaceContainerLow = Color(0xFF151A1E),
    surfaceContainer = Color(0xFF191F24), surfaceContainerHigh = Color(0xFF222930),
    surfaceContainerHighest = Color(0xFF2C343C),
    outline = Color(0xFF4A545D), outlineVariant = Color(0xFF2A3138),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

val SIGNAL_COLORS = listOf(0xFF4FC3F7, 0xFFFFB74D, 0xFF81C784, 0xFFE57373, 0xFFBA68C8, 0xFFFFF176, 0xFF4DB6AC, 0xFFF06292).map { Color(it) }
val BUS_COLORS = listOf(0xFF4FC3F7, 0xFFFFB74D, 0xFF81C784, 0xFFBA68C8).map { Color(it) }
val RECORDING = Color(0xFFFF5252)
val MARKER = Color(0xFFFFB74D)
val DIFFERENT = Color(0xFFEF6C00)
val VARIES_IN_ONE = Color(0xFF7E5A1E)

@Composable
fun CanPilotTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, shapes = AppShapes, content = content)
