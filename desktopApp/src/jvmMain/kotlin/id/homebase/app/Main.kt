package id.homebase.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.LocalLifecycleOwner
import chat_kmp.homebase_common.BuildConfig
import co.touchlab.kermit.Logger
import com.kdroid.composetray.tray.api.Tray
import com.kdroid.composetray.utils.SingleInstanceManager
import com.mmk.kmpnotifier.notification.NotifierManager
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.browser.LocalCallbackServer
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.file.JvmFileSystemUtil
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.sync.database.DatabaseKeyManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.app.lifecycle.rememberDesktopLifecycleOwner
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
import id.homebase.resources.update_available
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.startKoin
import java.io.File

fun main() {
    // Force JNA to load its bundled native library. Without this, Windows may resolve
    // a mismatched jnidispatch.dll from the system path, causing Native.<clinit> to throw.
    // Must be set before any JNA-using class (FileKit, compose-native-tray) loads.
    System.setProperty("jna.nosys", "true")

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

    // Bridge the local callback server's /permission-callback hit (after the user
    // returns from the owner-console "Extend Permissions" flow) to the in-app event
    // bus so ExtendPermissionViewModel re-runs its check and the dialog dismisses.
    val eventBus = GlobalContext.get().get<EventBus>()
    LocalCallbackServer.setPermissionCallback { canceled ->
        runBlocking {
            eventBus.emit(
                if (canceled) BackendEvent.PermissionsExtensionCanceled
                else BackendEvent.PermissionsExtensionReturned
            )
        }
    }

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
        try {
            DatabaseManager.initialize { DatabaseDriverFactory().createDriver(dbKey) }
        } catch (e: Exception) {
            Logger.e("Main", e, "Database init failed, resetting")
            // Delete the corrupted/undecryptable database file and its journal
            // files. Path resolution goes through DatabaseDriverFactory.resolveDbFile
            // so the two stay in lock-step. Mirrors the Android recovery path in
            // MainApplication.onCreate.
            val dbFile = DatabaseDriverFactory.resolveDbFile()
            dbFile.delete()
            File(dbFile.path + "-journal").delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()

            // Clear the stale encryption key and generate a fresh one
            DatabaseKeyManager.clearKey()
            val freshKey = DatabaseKeyManager.getOrGenerateKey()

            DatabaseManager.initialize { DatabaseDriverFactory().createDriver(freshKey) }
        }
    }

    application {
        val viewModel = koin.get<DesktopViewModel>()
        val uiState by viewModel.uiState.collectAsState()
        val icon = painterResource(MR.drawable.homebase_icon_round)
        var isWindowVisible by remember { mutableStateOf(true) }
        val updateAvailable = stringResource(MR.string.update_available)
        val state = rememberWindowState(
            placement = config.windowPlacement,
            position = config.windowPosition,
            width = maxOf(config.windowWidthDp, minWidth.dp), // Minimum width
            height = maxOf(config.windowHeightDp, minHeight.dp), // Minimum height
        )
        val themeLabel = uiState.theme.getStringResourceForTheme()

        val isSingleInstance = SingleInstanceManager.isSingleInstance(
            onRestoreRequest = {
                isWindowVisible = true  // Restore the existing window
            }
        )

        if (!isSingleInstance) {
            exitApplication()
            return@application
        }

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
            if (uiState.updateAvailable) {
                Item(
                    label = updateAvailable,
                    icon = Icons.Default.Update,
                ) {
                    viewModel.onUiAction(DesktopUiAction.TriggerUpdate)
                }
            }

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

            // Provide Desktop-specific LifecycleOwner to the composition tree
            val desktopLifecycleOwner = rememberDesktopLifecycleOwner(isWindowVisible)
            CompositionLocalProvider(LocalLifecycleOwner provides desktopLifecycleOwner) {
                App()
            }
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
