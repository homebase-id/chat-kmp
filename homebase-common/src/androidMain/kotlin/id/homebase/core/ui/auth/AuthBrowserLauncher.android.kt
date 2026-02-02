package id.homebase.core.ui.auth

import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri

/**
 * Android implementation using Chrome Custom Tabs with LocalContext. No more ActivityProvider
 * needed - we get context directly from Compose.
 */
@Composable
actual fun rememberAuthBrowserLauncher(): (url: String) -> Unit {
    val context = LocalContext.current

    return remember(context) {
        { url: String ->
            // Create custom color scheme to match app theme
            val colorSchemeParams =
                    CustomTabColorSchemeParams.Builder()
                            .setToolbarColor("#6750A4".toColorInt()) // Material You primary
                            .setSecondaryToolbarColor("#E7E0EC".toColorInt()) // Light background
                            .build()

            val customTabsIntent =
                    CustomTabsIntent.Builder()
                            .setDefaultColorSchemeParams(colorSchemeParams)
                            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
                            .setShowTitle(true)
                            .setUrlBarHidingEnabled(true)
                            .setCloseButtonPosition(CustomTabsIntent.CLOSE_BUTTON_POSITION_END)
                            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                            .build()

            customTabsIntent.launchUrl(context, url.toUri())
        }
    }
}
