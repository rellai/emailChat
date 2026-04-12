package com.emailchat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Цвета Material 3
private val md_theme_light_primary = androidx.compose.ui.graphics.Color(0xFF0061A4)
private val md_theme_light_onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
private val md_theme_light_primaryContainer = androidx.compose.ui.graphics.Color(0xFFD1E4FF)
private val md_theme_light_onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF001D36)
private val md_theme_light_secondaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE2F9)
private val md_theme_light_surfaceVariant = androidx.compose.ui.graphics.Color(0xFFDFE2EB)
private val md_theme_light_onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF43474E)

private val md_theme_dark_primary = androidx.compose.ui.graphics.Color(0xFF9ECAFF)
private val md_theme_dark_onPrimary = androidx.compose.ui.graphics.Color(0xFF003258)
private val md_theme_dark_primaryContainer = androidx.compose.ui.graphics.Color(0xFF00497D)
private val md_theme_dark_secondaryContainer = androidx.compose.ui.graphics.Color(0xFF303B57)
private val md_theme_dark_surfaceVariant = androidx.compose.ui.graphics.Color(0xFF43474E)
private val md_theme_dark_onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC3C6CF)

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondaryContainer = md_theme_light_secondaryContainer,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    secondaryContainer = md_theme_dark_secondaryContainer,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
)

@Composable
fun EmailChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}