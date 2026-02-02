package id.homebase.core.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.net.URI

/** Desktop (JVM) implementation using system browser. */
@Composable
actual fun rememberAuthBrowserLauncher(): (url: String) -> Unit {
    return remember {
        { url: String ->
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    }
}
