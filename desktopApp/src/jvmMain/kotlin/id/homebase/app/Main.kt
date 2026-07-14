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
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.app.lifecycle.rememberDesktopLifecycleOwner
import id.homebase.core.App
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.di.allModules
import id.homebase.core.diagnostics.MainThreadWatchdog
import id.homebase.api.client.isRecoverableServerConflict
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.app.crash.CrashRecoveryDialog
import id.homebase.core.crash.CrashMetadata
import id.homebase.core.crash.CrashReporting
import id.homebase.core.logging.CrashLogger
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.logging.StartupLogger
import id.homebase.core.logging.crashlyticsRecordException
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
        CrashReporting.install(
            metadata = CrashMetadata(
                appVersion = System.getProperty("jpackage.app-version") ?: "dev",
                buildType = "desktop",
                platform = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
                device = System.getProperty("os.arch") ?: "?",
                buildTime = BuildConfig.APP_BUILD_TIME,
            ),
            logDir = Path(File(JvmFileSystemUtil.getAppDataDirectory(), "logs").absolutePath),
        )
    } catch (e: Exception) {
        Logger.e("Main", e, "Failed to initialize file logging")
    }

    // Set up crash handler
    setupCrashHandler()
    StartupLogger.checkpoint("main() entered, file logger online")

    // Detect UI-thread (AWT EDT) stalls and log the EDT stack to homebase.log. Desktop has no
    // OS-level ANR, so without this a freeze leaves no evidence at all.
    MainThreadWatchdog().start()

    // Initialize the database BEFORE Koin. startKoin eagerly instantiates `createdAtStart`
    // singletons (e.g. DesktopChatNotificationBridge), and that chain resolves
    // `DatabaseManager.appDb`, which reads the lateinit `instance`. If the DB isn't initialized
    // first, eager creation throws UninitializedPropertyAccessException during startKoin. The
    // recovery open only needs DatabaseDriverFactory + DatabaseKeyManager — no Koin dependency —
    // so it's safe to run here.
    runBlocking {
        DatabaseManager.initializeWithRecovery(DatabaseDriverFactory())
    }
    StartupLogger.checkpoint("database initialized")

    // Initialize Koin
    startKoin { modules(allModules) }
    StartupLogger.checkpoint("Koin DI graph built")

    val platformInfo = GlobalContext.get().get<PlatformInfo>()
    StartupLogger.logAppStartupInfo(platformInfo.versionName, platformInfo.versionCode, BuildConfig.APP_BUILD_TIME)

    // Promote AuthConnectionCoordinator out of headless mode. AuthCC starts
    // headless = true to suppress foreground-only work (WebSocket connect,
    // driveRegistry.start, loadProfile, UI preload) during FCM cold-wakes on
    // mobile. Desktop has no background FCM wake — launching the process IS
    // the foreground signal — so without this call the Authenticated branch
    // defers connect() forever and the app hangs on "syncing" after login.
    // Mirrors MainActivity.onCreate (Android) and MainViewController() (iOS).
    // Idempotent and order-independent: if Authenticated hasn't fired yet it
    // simply flips the flag so the next Authenticated runs the full sequence.
    GlobalContext.get().get<AuthConnectionCoordinator>().promoteToForeground()

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
    LocalCallbackServer.setDataUpgradeCallback {
        runBlocking {
            eventBus.emit(BackendEvent.DataUpgradeReturned)
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
    StartupLogger.checkpoint("locale applied")

    val minWidth = 480
    val minHeight = 400
    val config = DesktopPreferences()

    application {
        remember { StartupLogger.checkpoint("Compose application{} entered") }
        val viewModel = koin.get<DesktopViewModel>()
        remember { StartupLogger.checkpoint("DesktopViewModel resolved") }
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
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        // Preserve PR #737: don't crash on a transient network blip. Also contain a
        // recoverable optimistic-concurrency conflict (400 VersionTagMismatch, #1008).
        if (throwable.isTransientNetworkFailure() || throwable.isRecoverableServerConflict()) {
            crashlyticsRecordException(throwable)
            Logger.w(tag = "CrashHandler") {
                val kind = if (throwable.isRecoverableServerConflict()) {
                    "Recoverable server conflict (stale versionTag; write dropped, drive-sync reconciles)"
                } else {
                    "Transient network failure"
                }
                "$kind leaked to '${thread.name}' (no local handler); " +
                    "app not crashing: ${throwable.message}"
            }
            return@setDefaultUncaughtExceptionHandler
        }
        try {
            CrashLogger.logCrash(thread.name, throwable)
            val reportPath = CrashReporting.writeReport(thread.name, throwable)
            // Modal dialog; it handles Restart/Reveal/Quit and owns termination.
            CrashRecoveryDialog.showAndBlock(reportPath?.toString())
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
}
