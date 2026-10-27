package id.homebase.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.window.ApplicationScope
import com.kdroid.composetray.menu.api.TrayMenuBuilder
import com.kdroid.composetray.tray.api.Tray
import com.kdroid.composetray.utils.isMenuBarInDarkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ApplicationScope.HomebaseTray(
    tooltip: String,
    callIndicatorLabel: String? = null,
    primaryAction: () -> Unit,
    menuContent: TrayMenuBuilder.() -> Unit,
) {
    // macOS reopens the hidden window from the Dock instead (AppReopenedListener in Main.kt).
    if (isMacOs) return
    // The menu bar follows the desktop picture, not the app theme, so this tints off
    // the bar's own appearance rather than a MaterialTheme role.
    val darkBar = isMenuBarInDarkMode()
    val callIndicator = callIndicatorLabel != null
    val icon by produceState<TrayIconFiles?>(null, darkBar, callIndicator) {
        value = withContext(Dispatchers.IO) { writeTrayIconFiles(darkBar, callIndicator) }
    }
    val files = icon ?: return
    @Suppress("DEPRECATION") // Its replacements render the icon through a skiko method that no longer exists.
    Tray(
        iconPath = files.pngPath,
        windowsIconPath = files.icoPath,
        tooltip = callIndicatorLabel?.let { "$tooltip, $it" } ?: tooltip,
        primaryAction = primaryAction,
        menuContent = menuContent,
    )
}
