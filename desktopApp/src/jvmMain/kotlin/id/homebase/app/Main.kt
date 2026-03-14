package id.homebase.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import co.touchlab.kermit.Logger
import com.mmk.kmpnotifier.notification.NotifierManager
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseKeyManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.App
import id.homebase.core.di.allModules
import id.homebase.core.logging.CrashLogger
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.settings.UserPreferences
import id.homebase.core.settings.applyStoredLocale
import id.homebase.resources.MR
import id.homebase.resources.app_name
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.startKoin
import java.io.File

fun main() = application {
    // Initialize Koin first
    startKoin { modules(allModules) }

    // Initialize file logging
    try {
        // Use user's home directory for logs
        val userHome = System.getProperty("user.home")
        val logsDir = File(userHome, ".homebase-chat/logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        LoggerConfig.initialize(logDirectory = Path(logsDir.absolutePath))
    } catch (e: Exception) {
        Logger.e("Main", e, "Failed to initialize file logging")
    }

    // Set up crash handler
    setupCrashHandler()

    // OSX customizations
    System.setProperty("apple.awt.application.appearance", "system")

    // Initialize FileKit
    FileKit.init(appId = "HomebaseChat")

    // Initialize KMPNotifier for desktop notifications
    NotifierManager.initialize(
        configuration = com.mmk.kmpnotifier.notification.configuration.NotificationPlatformConfiguration.Desktop(
            showPushNotification = true,
        )
    )

    // Apply saved locale
    val koin = GlobalContext.get()
    val userPreferences = koin.get<UserPreferences>()

    runBlocking { applyStoredLocale(userPreferences) }

    val minWidth = 480
    val minHeight = 400
    val config = DesktopPreferences()

    val state = rememberWindowState(
        placement = config.windowPlacement,
        position = config.windowPosition,
        width = maxOf(config.windowWidthDp, minWidth.dp), // Minimum width
        height = maxOf(config.windowHeightDp, minHeight.dp), // Minimum height
    )

    runBlocking {
        val dbKey = DatabaseKeyManager.getOrGenerateKey()
        DatabaseManager.initialize { DatabaseDriverFactory().createDriver(dbKey) }
    }

    Window(
        onCloseRequest = {
            try {
                config.windowPlacement = state.placement
                config.windowPosition = state.position
                config.windowWidthDp = maxOf(state.size.width, minWidth.dp)
                config.windowHeightDp = maxOf(state.size.height, minHeight.dp)
            } catch (_: Exception) {
                // Logger.w(TAG, e, "Error saving window state")
            }
            exitApplication()
        },
        alwaysOnTop = false,
        title = stringResource(MR.string.app_name),
        undecorated = false,
        state = state,
    ) {
        DesktopAppFocusManager.registerWindowProvider { window }
        window.minimumSize = java.awt.Dimension(minWidth, minHeight)
        App()
    }
}

private fun setupCrashHandler() {
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            CrashLogger.logCrash(thread.name, throwable)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Call the original handler to let the app crash normally
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
