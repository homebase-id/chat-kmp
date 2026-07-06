package id.homebase.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindow

@Composable
internal actual fun UpdateEdgeToEdge(darkTheme: Boolean, followsSystemTheme: Boolean) {
    SideEffect {
        val style = when {
            followsSystemTheme -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            darkTheme -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
            else -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
        }
        UIApplication.sharedApplication.windows.forEach {
            (it as? UIWindow)?.overrideUserInterfaceStyle = style
        }
    }
}
