package id.homebase.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.App
import id.homebase.core.di.allModules
import id.homebase.core.settings.UserPreferences
import id.homebase.core.settings.applyStoredLocale
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.startKoin

fun main() = application {
    // Initialize Koin first
    startKoin {
        modules(allModules)
    }

    // Apply saved locale
    val koin = GlobalContext.get()
    val userPreferences = koin.get<UserPreferences>()

    runBlocking {
        applyStoredLocale(userPreferences)
    }

    val config = DesktopPreferences()
    val state = rememberWindowState(
        placement = config.windowPlacement,
        position = config.windowPosition,
        width = config.windowWidthDp,
        height = config.windowHeightDp,
    )

    runBlocking {
        DatabaseManager.initialize { DatabaseDriverFactory().createDriver() }
    }

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
        DesktopAppFocusManager.registerWindowProvider { window }
        App()
    }
}
