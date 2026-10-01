package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = HexShardTeal,
    onPrimary = Color.White,
    primaryContainer = HexShardTealContainer,
    onPrimaryContainer = HexShardOnTealContainer,
    secondary = HexShardTealHover,
    onSecondary = Color.White,
    secondaryContainer = HexShardTealDim,
    onSecondaryContainer = HexShardOnTealContainer,
    tertiary = HexAiCyan,
    onTertiary = Color.White,
    background = HexDarkBg,
    onBackground = HexTextPrimary,
    surface = HexDarkSurface,
    onSurface = HexTextPrimary,
    surfaceVariant = HexDarkSurfaceHover,
    onSurfaceVariant = HexTextSecondary,
    surfaceContainerLowest = HexDarkBg,
    surfaceContainerLow = HexDarkSurface,
    surfaceContainer = HexDarkSurfaceElevated,
    surfaceContainerHigh = HexDarkSurfaceHover,
    surfaceContainerHighest = Color(0xFF243240),
    outline = HexDarkBorder,
    outlineVariant = HexDarkBorderSubtle,
    error = HexDanger,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = HexShardTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1F5E5),
    onPrimaryContainer = Color(0xFF01472B),
    secondary = HexShardTealHover,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2F8EE),
    onSecondaryContainer = Color(0xFF023621),
    tertiary = HexAiCyan,
    onTertiary = Color.White,
    background = HexLightBg,
    onBackground = HexLightTextPrimary,
    surface = HexLightSurface,
    onSurface = HexLightTextPrimary,
    surfaceVariant = HexLightSurfaceHover,
    onSurfaceVariant = HexLightTextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = HexLightSurfaceElevated,
    surfaceContainer = HexLightSurface,
    surfaceContainerHigh = HexLightSurfaceHover,
    surfaceContainerHighest = Color(0xFFCBD5E1),
    outline = HexLightBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = HexDanger,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // HexShard defaults to Dark Cinematic aesthetic
    dynamicColor: Boolean = false, // Preserve brand identity; dynamic colors disabled
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
