package com.example.mikuapp.ui.theme

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
    primary = MikuCyan,
    onPrimary = Color(0xFF00201E),
    primaryContainer = MikuCyanDark,
    onPrimaryContainer = MikuCyanLight,
    secondary = MikuPink,
    onSecondary = Color.White,
    tertiary = MikuCyanLight,
    background = MikuBackground,
    onBackground = MikuOnSurface,
    surface = MikuSurface,
    onSurface = MikuOnSurface,
    surfaceVariant = MikuSurfaceVariant,
    onSurfaceVariant = MikuMuted,
)

private val LightColorScheme = lightColorScheme(
    primary = MikuCyanDark,
    onPrimary = Color.White,
    secondary = MikuPink,
    onSecondary = Color.White,
    tertiary = MikuCyan,
    background = Color(0xFFF2FCFB),
    onBackground = Color(0xFF0A1F1D),
    surface = Color.White,
    onSurface = Color(0xFF0A1F1D),
)

@Composable
fun MikuAppTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
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
        content = content,
    )
}
