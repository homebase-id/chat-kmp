package id.homebase.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** Light color scheme using Signal-based LightColors */
private val LightColorScheme =
        lightColorScheme(
                primary = LightColors.Primary,
                onPrimary = LightColors.OnPrimary,
                primaryContainer = LightColors.PrimaryContainer,
                onPrimaryContainer = LightColors.OnPrimaryContainer,
                secondary = LightColors.Secondary,
                onSecondary = LightColors.OnSecondary,
                secondaryContainer = LightColors.SecondaryContainer,
                onSecondaryContainer = LightColors.OnSecondaryContainer,
                background = LightColors.Background,
                onBackground = LightColors.OnBackground,
                surface = LightColors.Surface,
                onSurface = LightColors.OnSurface,
                surfaceVariant = LightColors.SurfaceVariant,
                onSurfaceVariant = LightColors.OnSurfaceVariant,
                error = LightColors.Error,
                onError = LightColors.OnError,
                errorContainer = LightColors.ErrorContainer,
                onErrorContainer = LightColors.OnErrorContainer,
                outline = LightColors.Outline,
                surfaceContainerLowest = LightColors.Surface,
                surfaceContainerLow = LightColors.Surface1,
                surfaceContainer = LightColors.Surface2,
                surfaceContainerHigh = LightColors.Surface3,
                surfaceContainerHighest = LightColors.Surface4
        )

/** Dark color scheme using Signal-based DarkColors */
private val DarkColorScheme =
        darkColorScheme(
                primary = DarkColors.Primary,
                onPrimary = DarkColors.OnPrimary,
                primaryContainer = DarkColors.PrimaryContainer,
                onPrimaryContainer = DarkColors.OnPrimaryContainer,
                secondary = DarkColors.Secondary,
                onSecondary = DarkColors.OnSecondary,
                secondaryContainer = DarkColors.SecondaryContainer,
                onSecondaryContainer = DarkColors.OnSecondaryContainer,
                background = DarkColors.Background,
                onBackground = DarkColors.OnBackground,
                surface = DarkColors.Surface,
                onSurface = DarkColors.OnSurface,
                surfaceVariant = DarkColors.SurfaceVariant,
                onSurfaceVariant = DarkColors.OnSurfaceVariant,
                error = DarkColors.Error,
                onError = DarkColors.OnError,
                errorContainer = DarkColors.ErrorContainer,
                onErrorContainer = DarkColors.OnErrorContainer,
                outline = DarkColors.Outline,
                surfaceContainerLowest = DarkColors.Surface,
                surfaceContainerLow = DarkColors.Surface1,
                surfaceContainer = DarkColors.Surface2,
                surfaceContainerHigh = DarkColors.Surface3,
                surfaceContainerHighest = DarkColors.Surface4
        )

/** Extended colors not covered by Material 3 ColorScheme */
data class HomebaseExtendedColors(
        val surface1: androidx.compose.ui.graphics.Color,
        val surface2: androidx.compose.ui.graphics.Color,
        val surface3: androidx.compose.ui.graphics.Color,
        val surface4: androidx.compose.ui.graphics.Color,
        val surface5: androidx.compose.ui.graphics.Color,
        val transparent1: androidx.compose.ui.graphics.Color,
        val transparent2: androidx.compose.ui.graphics.Color,
        val transparent3: androidx.compose.ui.graphics.Color,
        val transparent4: androidx.compose.ui.graphics.Color,
        val transparent5: androidx.compose.ui.graphics.Color,
        val neutral: androidx.compose.ui.graphics.Color,
        val neutralVariant: androidx.compose.ui.graphics.Color,
        val neutralSurface: androidx.compose.ui.graphics.Color,
        val onCustom: androidx.compose.ui.graphics.Color,
        val onCustomVariant: androidx.compose.ui.graphics.Color,
        val onSurfaceVariant1: androidx.compose.ui.graphics.Color,
        val bubbleSentSurface: androidx.compose.ui.graphics.Color,
        val bubbleSentOnSurface: androidx.compose.ui.graphics.Color,
        val warning: androidx.compose.ui.graphics.Color,
        /** Live-location sharing indicator (#816) — Homebase purple, same value both themes. */
        val liveSharing: androidx.compose.ui.graphics.Color,
)

private val LightExtendedColors =
        HomebaseExtendedColors(
                surface1 = LightColors.Surface1,
                surface2 = LightColors.Surface2,
                surface3 = LightColors.Surface3,
                surface4 = LightColors.Surface4,
                surface5 = LightColors.Surface5,
                transparent1 = LightColors.Transparent1,
                transparent2 = LightColors.Transparent2,
                transparent3 = LightColors.Transparent3,
                transparent4 = LightColors.Transparent4,
                transparent5 = LightColors.Transparent5,
                neutral = LightColors.Neutral,
                neutralVariant = LightColors.NeutralVariant,
                neutralSurface = LightColors.NeutralSurface,
                onCustom = LightColors.OnCustom,
                onCustomVariant = LightColors.OnCustomVariant,
                onSurfaceVariant1 = LightColors.OnSurfaceVariant1,
                bubbleSentSurface = LightColors.Primary,
                bubbleSentOnSurface = LightColors.OnPrimary,
                warning = ExtendedColors.Warning,
                liveSharing = ExtendedColors.LiveSharing,
        )

private val DarkExtendedColors =
        HomebaseExtendedColors(
                surface1 = DarkColors.Surface1,
                surface2 = DarkColors.Surface2,
                surface3 = DarkColors.Surface3,
                surface4 = DarkColors.Surface4,
                surface5 = DarkColors.Surface5,
                transparent1 = DarkColors.Transparent1,
                transparent2 = DarkColors.Transparent2,
                transparent3 = DarkColors.Transparent3,
                transparent4 = DarkColors.Transparent4,
                transparent5 = DarkColors.Transparent5,
                neutral = DarkColors.Neutral,
                neutralVariant = DarkColors.NeutralVariant,
                neutralSurface = DarkColors.NeutralSurface,
                onCustom = DarkColors.OnCustom,
                onCustomVariant = DarkColors.OnCustomVariant,
                onSurfaceVariant1 = DarkColors.OnSurfaceVariant1,
                bubbleSentSurface = LightColors.Primary,
                bubbleSentOnSurface = LightColors.OnPrimary,
                warning = ExtendedColors.Warning,
                liveSharing = ExtendedColors.LiveSharing,
        )

val LocalHomebaseExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/**
 * Homebase app theme with dark/light mode support. Uses Signal-based color palette.
 *
 * @param darkTheme Whether to use dark theme. Defaults to system setting.
 * @param content The content to display with this theme.
 */
@Composable
fun HomebaseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
        val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
        val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

        CompositionLocalProvider(LocalHomebaseExtendedColors provides extendedColors) {
                MaterialTheme(
                        colorScheme = colorScheme,
                        typography = HomebaseTypography,
                        content = content
                )
        }
}

/**
 * Access extended colors that are not part of Material 3 ColorScheme.
 *
 * Usage: HomebaseTheme.extendedColors.surface1
 */
object HomebaseTheme {
        val extendedColors: HomebaseExtendedColors
                @Composable get() = LocalHomebaseExtendedColors.current
}
