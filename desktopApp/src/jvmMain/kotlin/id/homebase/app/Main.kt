package id.homebase.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.video.LocalVideoServer
import id.homebase.core.App
import id.homebase.core.di.allModules
import id.homebase.core.settings.UserPreferences
import id.homebase.core.settings.applyStoredLocale
import id.homebase.resources.MR
import id.homebase.resources.app_name
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.startKoin
import org.koin.dsl.module

fun main() {
    // 🔹 VLC native discovery — MUST be before Compose application()
    val found = uk.co.caprica.vlcj.factory.discovery.NativeDiscovery().discover()
    require(found) { "VLC native libraries not found" }

    application {

        // OSX customizations
        System.setProperty("apple.awt.application.appearance", "system")

        // Initialize FileKit
        FileKit.init(appId = "HomebaseChat")

        // --- START VIDEO SERVER (runtime-owned) ---
        val videoServer = runBlocking {
            LocalVideoServer().also { it.start() }
        }

        // --- START KOIN FIRST ---
        startKoin {
            modules(
                allModules + module {
                    single<LocalVideoServer> { videoServer }
                }
            )
        }

        // --- NOW SAFE TO USE DI ---
        val userPreferences: UserPreferences =
            org.koin.java.KoinJavaComponent.get(UserPreferences::class.java)

        runBlocking {
            applyStoredLocale(userPreferences)
        }

        runBlocking {
            DatabaseManager.initialize { DatabaseDriverFactory().createDriver() }
        }

        val minWidth = 480
        val minHeight = 400
        val config = DesktopPreferences()

        val state = rememberWindowState(
            placement = config.windowPlacement,
            position = config.windowPosition,
            width = maxOf(config.windowWidthDp, minWidth.dp),
            height = maxOf(config.windowHeightDp, minHeight.dp),
        )

        Window(
            onCloseRequest = {
                config.windowPlacement = state.placement
                config.windowPosition = state.position
                config.windowWidthDp = maxOf(state.size.width, minWidth.dp)
                config.windowHeightDp = maxOf(state.size.height, minHeight.dp)
                exitApplication()
            },
            title = stringResource(MR.string.app_name),
            state = state,
        ) {
            DesktopAppFocusManager.registerWindowProvider { window }
            window.minimumSize = java.awt.Dimension(minWidth, minHeight)
            App()
        }
    }
}
