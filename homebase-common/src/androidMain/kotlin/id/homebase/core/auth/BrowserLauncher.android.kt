package id.homebase.core.auth

import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope


actual object BrowserLauncher {
    actual fun launchAuthBrowser(
        url: String,
        scope: CoroutineScope,
        onOpenUrl: (String) -> Unit
    ) {

        val activity = ActivityProvider.requireActivity()
        // Create custom color scheme to match app theme
        val colorSchemeParams =
            CustomTabColorSchemeParams.Builder()
                .setToolbarColor(
                    "#6750A4".toColorInt()
                ) // Material You primary color
                .setSecondaryToolbarColor(
                    "#E7E0EC".toColorInt()
                ) // Light background
                .build()

//        val pendingIntent =
//            ActivityOptions.makeCustomAnimation(
//                activity,
//                R.anim.fade_in,
//                R.anim.fade_out
//            )
//                .toBundle()

        val customTabsIntent =
            CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(colorSchemeParams)
                .setColorScheme(
                    CustomTabsIntent.COLOR_SCHEME_SYSTEM
                ) // Follow system theme
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .setCloseButtonPosition(CustomTabsIntent.CLOSE_BUTTON_POSITION_END)
                .setShareState(
                    CustomTabsIntent.SHARE_STATE_OFF
                ) // Disable share button for auth flow
                .build()

//        customTabsIntent.intent.putExtra(
//            CustomTabsIntent.EXTRA_EXIT_ANIMATION_BUNDLE,
//            pendingIntent
//        )

        customTabsIntent.launchUrl(activity, url.toUri())
        onOpenUrl(url)
    }
}