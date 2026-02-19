package com.retichat.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary          = AccentBlue,
    onPrimary        = Color.White,
    primaryContainer = AccentBlue.copy(alpha = 0.12f),
    secondary        = AccentGreen,
    onSecondary      = Color.White,
    background       = BackgroundLight,
    onBackground     = TextPrimary,
    surface          = SurfaceLight,
    onSurface        = TextPrimary,
    surfaceVariant   = GlassWhite,
    onSurfaceVariant = TextSecondary,
    outline          = GlassBorder,
    error            = AccentRed,
    onError          = Color.White,
)

private val DarkColors = darkColorScheme(
    primary          = AccentBlue,
    onPrimary        = Color.White,
    primaryContainer = AccentBlue.copy(alpha = 0.24f),
    secondary        = AccentGreen,
    onSecondary      = Color.White,
    background       = BackgroundDark,
    onBackground     = TextPrimaryDark,
    surface          = SurfaceDark,
    onSurface        = TextPrimaryDark,
    surfaceVariant   = Color(0xFF2C2C2E),
    onSurfaceVariant = TextSecondaryDark,
    outline          = Color(0xFF3A3A3C),
    error            = AccentRed,
    onError          = Color.White,
)

@Composable
fun RetichatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx)
            else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RetichatTypography,
        shapes = Shapes(
            small  = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large  = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
