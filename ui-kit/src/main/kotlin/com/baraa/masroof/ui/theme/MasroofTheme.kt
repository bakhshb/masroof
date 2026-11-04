package com.baraa.masroof.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.util.Locale

private val LightColorScheme = lightColorScheme(
    primary = MasroofLightColors.Primary,
    onPrimary = MasroofLightColors.OnPrimary,
    primaryContainer = MasroofLightColors.PrimaryContainer,
    onPrimaryContainer = MasroofLightColors.OnPrimaryContainer,
    secondaryContainer = MasroofLightColors.SecondaryContainer,
    onSecondaryContainer = MasroofLightColors.OnSecondaryContainer,
    tertiary = MasroofLightColors.Tertiary,
    onTertiary = MasroofLightColors.OnTertiary,
    background = MasroofLightColors.Background,
    onBackground = MasroofLightColors.OnSurface,
    surface = MasroofLightColors.Surface,
    onSurface = MasroofLightColors.OnSurface,
    onSurfaceVariant = MasroofLightColors.OnSurfaceVariant,
    surfaceVariant = MasroofLightColors.SurfaceVariant,
    surfaceDim = MasroofLightColors.SurfaceDim,
    surfaceBright = MasroofLightColors.SurfaceBright,
    surfaceContainerLowest = MasroofLightColors.SurfaceContainerLowest,
    surfaceContainerLow = MasroofLightColors.SurfaceContainerLow,
    surfaceContainer = MasroofLightColors.SurfaceContainer,
    surfaceContainerHigh = MasroofLightColors.SurfaceContainerHigh,
    surfaceContainerHighest = MasroofLightColors.SurfaceContainerHighest,
    outline = MasroofLightColors.Outline,
    outlineVariant = MasroofLightColors.OutlineVariant,
    error = MasroofLightColors.Error,
    onError = MasroofLightColors.OnError,
    errorContainer = MasroofLightColors.ErrorContainer,
    onErrorContainer = MasroofLightColors.OnErrorContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary = MasroofDarkColors.Primary,
    onPrimary = MasroofDarkColors.OnPrimary,
    primaryContainer = MasroofDarkColors.PrimaryContainer,
    onPrimaryContainer = MasroofDarkColors.OnPrimaryContainer,
    secondaryContainer = MasroofDarkColors.SecondaryContainer,
    onSecondaryContainer = MasroofDarkColors.OnSecondaryContainer,
    tertiary = MasroofDarkColors.Tertiary,
    onTertiary = MasroofDarkColors.OnTertiary,
    background = MasroofDarkColors.Background,
    onBackground = MasroofDarkColors.OnSurface,
    surface = MasroofDarkColors.Surface,
    onSurface = MasroofDarkColors.OnSurface,
    onSurfaceVariant = MasroofDarkColors.OnSurfaceVariant,
    surfaceVariant = MasroofDarkColors.SurfaceVariant,
    surfaceDim = MasroofDarkColors.SurfaceDim,
    surfaceBright = MasroofDarkColors.SurfaceBright,
    surfaceContainerLowest = MasroofDarkColors.SurfaceContainerLowest,
    surfaceContainerLow = MasroofDarkColors.SurfaceContainerLow,
    surfaceContainer = MasroofDarkColors.SurfaceContainer,
    surfaceContainerHigh = MasroofDarkColors.SurfaceContainerHigh,
    surfaceContainerHighest = MasroofDarkColors.SurfaceContainerHighest,
    outline = MasroofDarkColors.Outline,
    outlineVariant = MasroofDarkColors.OutlineVariant,
    error = MasroofDarkColors.Error,
    onError = MasroofDarkColors.OnError,
    errorContainer = MasroofDarkColors.ErrorContainer,
    onErrorContainer = MasroofDarkColors.OnErrorContainer,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MasroofTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val layoutDirection = if (languageTag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT) != "en") {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) darkExtendedColors() else lightExtendedColors()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            // Android 15+ ignores these (always edge-to-edge); Android 8-14 still paints the bars with them.
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.surface.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalMasroofExtendedColors provides extendedColors,
        // Material3 1.4 ignores the old enforcement flag; size 0 keeps touch targets at their drawn size.
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MasroofTypography,
            shapes = MasroofShapes,
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** True when the active Masroof theme is the dark one. */
@Composable
fun masroofIsDark(): Boolean = MaterialTheme.colorScheme.background == MasroofDarkColors.Background

object MasroofThemeExtras {
    val extendedColors
        @Composable
        get() = LocalMasroofExtendedColors.current
}
