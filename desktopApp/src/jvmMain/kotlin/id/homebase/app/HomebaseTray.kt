package id.homebase.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ApplicationScope
import com.kdroid.composetray.menu.api.TrayMenuBuilder
import com.kdroid.composetray.tray.api.Tray
import com.kdroid.composetray.utils.isMenuBarInDarkMode
import id.homebase.resources.MR
import id.homebase.resources.homebase_icon_mono
import org.jetbrains.compose.resources.painterResource

@Composable
fun ApplicationScope.HomebaseTray(
    tooltip: String,
    callActive: Boolean = false,
    primaryAction: () -> Unit,
    menuContent: TrayMenuBuilder.() -> Unit,
) {
    // macOS reopens the hidden window from the Dock instead (AppReopenedListener in Main.kt).
    if (isMacOs) return
    Tray(
        // The menu bar follows the desktop picture, not the app theme, so this tints off
        // the bar's own appearance rather than a MaterialTheme role.
        iconContent = {
            Box(Modifier.fillMaxSize()) {
                Icon(
                    painter = painterResource(MR.drawable.homebase_icon_mono),
                    contentDescription = tooltip,
                    tint = if (isMenuBarInDarkMode()) Color.White else Color.Black,
                    modifier = Modifier.fillMaxSize(),
                )
                if (callActive) {
                    Box(Modifier.align(Alignment.TopEnd).fillMaxSize(0.38f).background(CALL_INDICATOR, CircleShape))
                }
            }
        },
        tooltip = tooltip,
        primaryAction = primaryAction,
        menuContent = menuContent,
    )
}

// The menu bar sits outside MaterialTheme, so the live-call dot is a fixed recording red.
private val CALL_INDICATOR = Color(0xFFE53935)
