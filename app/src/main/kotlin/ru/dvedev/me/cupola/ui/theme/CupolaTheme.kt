package ru.dvedev.me.cupola.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/** User-facing theme preference (settings → «Тема»). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun ThemeMode.resolvesToDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Provides Cupola colour, typography and colormap tokens, and maps them onto a Material 3
 * colour scheme so that stock components used in settings pick the same palette.
 */
@Composable
fun CupolaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkCupolaColors else LightCupolaColors
    val colormap = remember(colors) { SpectrogramColormap(colors.spectrogramStops) }
    val typography = CupolaTypography()

    CompositionLocalProvider(
        LocalCupolaColors provides colors,
        LocalCupolaTypography provides typography,
        LocalSpectrogramColormap provides colormap,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = MaterialTheme.typography.copy(
                bodyLarge = typography.body,
                bodyMedium = typography.body,
                labelLarge = typography.button,
                titleMedium = typography.title,
            ),
            content = content,
        )
    }
}

val LocalSpectrogramColormap = androidx.compose.runtime.staticCompositionLocalOf {
    SpectrogramColormap(LightCupolaColors.spectrogramStops)
}

/** Accessors: `CupolaTheme.colors.gold`, `CupolaTheme.type.note`. */
object CupolaTheme {
    val colors: CupolaColors
        @Composable @ReadOnlyComposable get() = LocalCupolaColors.current
    val type: CupolaTypography
        @Composable @ReadOnlyComposable get() = LocalCupolaTypography.current
    val colormap: SpectrogramColormap
        @Composable @ReadOnlyComposable get() = LocalSpectrogramColormap.current
}

private fun CupolaColors.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = violetInk,
        onPrimary = panel,
        primaryContainer = panel2,
        onPrimaryContainer = violetInk,
        secondary = goldInk,
        onSecondary = panel,
        tertiary = ok,
        background = panel,
        onBackground = ink,
        surface = panel,
        onSurface = ink,
        surfaceVariant = panel2,
        onSurfaceVariant = mut,
        outline = line2,
        outlineVariant = line,
        error = bad,
        onError = panel,
    )
} else {
    lightColorScheme(
        primary = violetInk,
        onPrimary = Color.White,
        primaryContainer = panel2,
        onPrimaryContainer = violetInk,
        secondary = goldInk,
        onSecondary = Color.White,
        tertiary = ok,
        background = panel,
        onBackground = ink,
        surface = panel,
        onSurface = ink,
        surfaceVariant = panel2,
        onSurfaceVariant = mut,
        outline = line2,
        outlineVariant = line,
        error = bad,
        onError = Color.White,
    )
}
