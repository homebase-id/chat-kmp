package id.homebase.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import chat_kmp.homebase_common.BuildConfig
import co.touchlab.kermit.Logger
import com.kdroid.composetray.tray.api.Tray
import com.mmk.kmpnotifier.notification.NotifierManager
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.file.JvmFileSystemUtil
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseKeyManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.App
import id.homebase.core.di.allModules
import id.homebase.core.logging.CrashLogger
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.logging.StartupLogger
import id.homebase.core.settings.UserPreferences
import id.homebase.core.settings.applyStoredLocale
import id.homebase.core.ui.screens.appearance.getIconForTheme
import id.homebase.core.ui.screens.appearance.getStringResourceForTheme
import id.homebase.core.ui.screens.desktop.DesktopUiAction
import id.homebase.core.ui.screens.desktop.DesktopViewModel
import id.homebase.core.util.PlatformInfo
import id.homebase.resources.MR
import id.homebase.resources.app_name
import id.homebase.resources.homebase_icon_round
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.startKoin
import java.io.File

fun main() {
    // Initialize file logging
    try {
        // Use user's home directory for logs
        val userHome = JvmFileSystemUtil.getAppDataDirectory()
        val logsDir = File(userHome, "logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        LoggerConfig.initialize(logDirectory = Path(logsDir.absolutePath))
    } catch (e: Exception) {
        Logger.e("Main", e, "Failed to initialize file logging")
    }

    // Set up crash handler
    setupCrashHandler()

    // Initialize Koin first
    startKoin { modules(allModules) }

    val platformInfo = GlobalContext.get().get<PlatformInfo>()
    StartupLogger.logAppStartupInfo(platformInfo.versionName, platformInfo.versionCode, BuildConfig.APP_BUILD_TIME)

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

    runBlocking {
        val dbKey = DatabaseKeyManager.getOrGenerateKey()
        DatabaseManager.initialize { DatabaseDriverFactory().createDriver(dbKey) }
    }

    application {
        val viewModel = koin.get<DesktopViewModel>()
        val uiState by viewModel.uiState.collectAsState()
        val icon = painterResource(MR.drawable.homebase_icon_round)
        var isWindowVisible by remember { mutableStateOf(true) }
//        var notificationsEnabled by remember { mutableStateOf(true) }
        val state = rememberWindowState(
            placement = config.windowPlacement,
            position = config.windowPosition,
            width = maxOf(config.windowWidthDp, minWidth.dp), // Minimum width
            height = maxOf(config.windowHeightDp, minHeight.dp), // Minimum height
        )
        val themeLabel = uiState.theme.getStringResourceForTheme()

        Tray(
            icon = icon,
            tooltip = stringResource(MR.string.app_name),
            primaryAction = {
                isWindowVisible = !isWindowVisible
            },
        ) {
            Item(label = "Show Window") {
                isWindowVisible = true
            }

            Divider()
            Item(label = "Theme", isEnabled = false)
            Item(
                label = themeLabel,
                icon = uiState.theme.getIconForTheme(),
            ) {
                viewModel.onUiAction(DesktopUiAction.ToggleTheme)
            }

            Divider()

            // Reactive checkable item
//            CheckableItem(
//                label = "Notifications",
//                checked = notificationsEnabled,
//                onCheckedChange = { notificationsEnabled = it }
//            )
//
//            Divider()

            Item(label = "Homebase Chat", isEnabled = false)
            Item(label = "Version ${uiState.version}", isEnabled = false)

            Divider()

            // Quit option
            Item(label = "Quit") {
                try {
                    config.windowPlacement = state.placement
                    config.windowPosition = state.position
                    config.windowWidthDp = maxOf(state.size.width, minWidth.dp)
                    config.windowHeightDp = maxOf(state.size.height, minHeight.dp)
                } catch (_: Exception) {
                    // ignore
                }
                exitApplication()  // This properly exits the application
            }
        }

        Window(
            onCloseRequest = {
                try {
                    config.windowPlacement = state.placement
                    config.windowPosition = state.position
                    config.windowWidthDp = maxOf(state.size.width, minWidth.dp)
                    config.windowHeightDp = maxOf(state.size.height, minHeight.dp)
                } catch (_: Exception) {
                    // ignore
                }
                isWindowVisible = false
            },
            alwaysOnTop = false,
            title = stringResource(MR.string.app_name),
            icon = icon,
            state = state,
            visible = isWindowVisible
        ) {
            DesktopAppFocusManager.registerWindowProvider { window }
            window.minimumSize = java.awt.Dimension(minWidth, minHeight)
            App()
        }
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
