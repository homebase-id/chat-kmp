package id.homebase.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import id.homebase.core.KoinApp

fun main() = application {
    val config = DesktopPreferences()
    val state = rememberWindowState(
        placement = config.windowPlacement,
        position = config.windowPosition,
        width = config.windowWidthDp,
        height = config.windowHeightDp,
    )

    Window(
        onCloseRequest = {
            try {
                config.windowPlacement = state.placement
                config.windowPosition = state.position
                config.windowWidthDp = state.size.width
                config.windowHeightDp = state.size.height
            } catch (_: Exception) {
                //Logger.w(TAG, e, "Error saving window state")
            }
            exitApplication()
        },
        alwaysOnTop = true,
        title = "Homebase Chat",
        state = state,
    ) {
        KoinApp()
    }
}
