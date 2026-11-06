package id.homebase.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.CompositionLocalProvider
import id.homebase.common.util.WindowTitle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.LocalLifecycleOwner
import chat_kmp.homebase_common.BuildConfig
import co.touchlab.kermit.Logger
import com.mmk.kmpnotifier.notification.NotifierManager
import dev.nucleusframework.core.runtime.SingleInstanceManager
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.browser.LocalCallbackServer
import id.homebase.core.AppStartup
import id.homebase.core.notifications.DesktopChatNotificationBridge
import id.homebase.core.util.HostOs
import id.homebase.core.util.hostOs
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.file.JvmFileSystemUtil
import id.homebase.api.storage.SecureStorage
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.app.lifecycle.rememberDesktopLifecycleOwner
import id.homebase.core.App
import id.homebase.core.desktop.AppIconBadge
import id.homebase.core.diagnostics.MainThreadWatchdog
import id.homebase.app.diagnostics.EdtHitchMonitor
import id.homebase.api.client.isRecoverablePermissionFailure
import id.homebase.api.client.isRecoverableServerConflict
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.app.crash.CrashRecoveryDialog
import id.homebase.core.crash.CrashMetadata
import id.homebase.core.crash.CrashReporting
import id.homebase.core.logging.CrashLogger
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.logging.StartupLogger
import id.homebase.core.logging.crashlyticsRecordException
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import id.homebase.core.settings.applyStoredLocale
import id.homebase.core.ui.navigation.Route
import id.homebase.core.ui.screens.appearance.getIconForTheme
import id.homebase.core.ui.screens.appearance.getStringResourceForTheme
import id.homebase.core.ui.screens.desktop.DesktopUiAction
import id.homebase.core.ui.screens.desktop.DesktopViewModel
import id.homebase.resources.MR
import id.homebase.resources.app_name
import id.homebase.resources.chat_new_conversation
import id.homebase.resources.desktop_menu_call
import id.homebase.resources.desktop_menu_call_answer
import id.homebase.resources.desktop_menu_call_decline
import id.homebase.resources.desktop_menu_close_window
import id.homebase.resources.desktop_menu_file
import id.homebase.resources.desktop_menu_quit
import id.homebase.resources.cancel
import id.homebase.resources.keystore_reset_confirm
import id.homebase.resources.keystore_reset_confirm_message
import id.homebase.resources.keystore_reset_confirm_title
import id.homebase.resources.keystore_unavailable_message
import id.homebase.resources.keystore_unavailable_quit
import id.homebase.resources.keystore_unavailable_reset
import id.homebase.resources.keystore_unavailable_retry
import id.homebase.resources.keystore_unavailable_title
import id.homebase.resources.desktop_tray_call_indicator
import id.homebase.resources.desktop_tray_show_window
import id.homebase.resources.desktop_tray_version
import id.homebase.resources.homebase_icon_round
import id.homebase.resources.theme
import id.homebase.resources.update_available
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlinx.io.files.Path
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext
import java.awt.Desktop
import java.awt.Frame
import java.awt.desktop.AppReopenedListener
import java.awt.event.WindowStateListener
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
fun main() {
    // Off by default on desktop and web; without it custom text context menu items are dropped.
    ComposeFoundationFlags.isNewContextMenuEnabled = true
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
    configureSmokeLogging()
    StartupLogger.checkpoint("main() entered, file logger online")

    installSwingDoubleBufferSizeGuard()

    // Detect UI-thread (AWT EDT) stalls and log the EDT stack to homebase.log. Desktop has no
    // OS-level ANR, so without this a freeze leaves no evidence at all.
    MainThreadWatchdog().start()
    EdtHitchMonitor().start()

    AppStartup.startKoin()
    StartupLogger.checkpoint("Koin DI graph built")

    openDatabaseOrQuit()
    StartupLogger.checkpoint("database initialized")
    // Before afterDatabase: the call coordinator's fallback notifier needs it.
    NotifierManager.initialize(
        configuration = com.mmk.kmpnotifier.notification.configuration.NotificationPlatformConfiguration.Desktop(
            showPushNotification = true,
        )
    )
    // Before anything syncs: the bridge must be listening for the first batch.
    AppStartup.afterDatabase {
        GlobalContext.get().get<DesktopChatNotificationBridge>().start()
        id.homebase.core.calling.startDesktopCallCoordinator()
    }

    AppStartup.promoteToForeground()

    // Bridge the local callback server's /permission-callback hit (after the user
    // returns from the owner-console "Extend Permissions" flow) to the in-app event
    // bus so ExtendPermissionViewModel re-runs its check and the dialog dismisses.
    val eventBus = GlobalContext.get().get<EventBus>()
    LocalCallbackServer.setPermissionCallback { callback ->
        runBlocking { eventBus.emit(callback.event) }
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

    // Apply saved locale
    val koin = GlobalContext.get()
    val userPreferences = koin.get<UserPreferences>()
    val youAuthFlowManager = koin.get<YouAuthFlowManager>()

    runBlocking { applyStoredLocale(userPreferences) }
    StartupLogger.checkpoint("locale applied")

    val minWidth = 480
    val minHeight = 400
    val config = DesktopPreferences()

    val viewModel = koin.get<DesktopViewModel>()
    StartupLogger.checkpoint("DesktopViewModel resolved")

    val callWindow = koin.get<id.homebase.chat.calling.CallWindowModel>()

    application {
        remember { StartupLogger.checkpoint("Compose application{} entered") }
        val uiState by viewModel.uiState.collectAsState()
        val icon = painterResource(MR.drawable.homebase_icon_round)
        var isWindowVisible by remember { mutableStateOf(true) }
        // compose-native-tray composes its menu in a detached composition, so every tray
        // label has to be resolved out here.
        val appName = stringResource(MR.string.app_name)
        val updateAvailable = stringResource(MR.string.update_available)
        val showWindowLabel = stringResource(MR.string.desktop_tray_show_window)
        val themeMenuLabel = stringResource(MR.string.theme)
        val quitLabel = stringResource(MR.string.desktop_menu_quit)
        val versionLabel = stringResource(MR.string.desktop_tray_version, uiState.version)
        val state = rememberWindowState(
            placement = config.windowPlacement,
            position = config.windowPosition,
            width = maxOf(config.windowWidthDp, minWidth.dp), // Minimum width
            height = maxOf(config.windowHeightDp, minHeight.dp), // Minimum height
        )
        val themeLabel = uiState.theme.getStringResourceForTheme()

        val authState by youAuthFlowManager.authState.collectAsState()
        var navigateTo by remember { mutableStateOf<((Route) -> Unit)?>(null) }
        val canNavigate = navigateTo != null && authState is YouAuthState.Authenticated

        val saveWindowGeometry = {
            try {
                config.windowPlacement = state.placement
                config.windowPosition = state.position
                config.windowWidthDp = maxOf(state.size.width, minWidth.dp)
                config.windowHeightDp = maxOf(state.size.height, minHeight.dp)
            } catch (_: Exception) {
                // ignore
            }
        }
        val hideWindow = {
            saveWindowGeometry()
            isWindowVisible = false
        }
        val quitApplication = {
            saveWindowGeometry()
            id.homebase.core.calling.endDesktopCallForQuit()
            exitApplication()
        }

        val isSingleInstance = SingleInstanceManager.isSingleInstance(
            onRestoreRequest = {
                isWindowVisible = true  // Restore the existing window
            }
        )

        if (!isSingleInstance) {
            exitApplication()
            return@application
        }

        CallWindow(callWindow, config, uiState.theme)

        // Quit and Settings belong to the macOS app menu, which AppKit owns above the
        // JMenuBar — the app-menu Quit would otherwise exit without persisting geometry,
        // and no Preferences item appears at all until a handler is registered.
        val currentNavigate = rememberUpdatedState(navigateTo)
        DisposableEffect(Unit) {
            val awtDesktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            val quitHandlerDesktop =
                awtDesktop?.takeIf { it.isSupported(Desktop.Action.APP_QUIT_HANDLER) }
            quitHandlerDesktop?.setQuitHandler { _, response ->
                saveWindowGeometry()
                id.homebase.core.calling.endDesktopCallForQuit()
                response.performQuit()
            }
            val prefsDesktop =
                awtDesktop?.takeIf { it.isSupported(Desktop.Action.APP_PREFERENCES) }
            prefsDesktop?.setPreferencesHandler {
                currentNavigate.value?.invoke(Route.Settings)
            }
            val reopenDesktop =
                awtDesktop?.takeIf { it.isSupported(Desktop.Action.APP_EVENT_REOPENED) }
            val reopenListener = AppReopenedListener { isWindowVisible = true }
            reopenDesktop?.addAppEventListener(reopenListener)
            onDispose {
                quitHandlerDesktop?.setQuitHandler(null)
                prefsDesktop?.setPreferencesHandler(null)
                reopenDesktop?.removeAppEventListener(reopenListener)
            }
        }

        val appLifecycleOwner = rememberApplicationLifecycleOwner()
        // The ring window never takes focus, so its answer and decline shortcuts live in this menu.
        val callRinging by callWindow.ringing.collectAsStateWithLifecycle(lifecycleOwner = appLifecycleOwner)
        val callEntries = collectSettledCallEntries(callWindow.trayItems, appLifecycleOwner)
        CallDockMenu(callEntries, callWindow::perform)
        val callActiveLabel = stringResource(MR.string.desktop_tray_call_indicator)

        HomebaseTray(
            tooltip = appName,
            callIndicatorLabel = callActiveLabel.takeIf { callEntries.isNotEmpty() },
            primaryAction = {
                isWindowVisible = !isWindowVisible
            },
        ) {
            callEntries.forEach { entry ->
                Item(label = entry.label) { callWindow.perform(entry.action) }
            }
            if (callEntries.isNotEmpty()) Divider()

            Item(label = showWindowLabel) {
                isWindowVisible = true
            }

            Divider()
            Item(label = themeMenuLabel, isEnabled = false)
            Item(
                label = themeLabel,
                icon = uiState.theme.getIconForTheme(),
            ) {
                viewModel.onUiAction(DesktopUiAction.ToggleTheme)
            }

            Divider()

            Item(label = appName, isEnabled = false)
            Item(label = versionLabel, isEnabled = false)
            if (uiState.updateAvailable) {
                Item(
                    label = updateAvailable,
                    icon = Icons.Default.Update,
                ) {
                    viewModel.onUiAction(DesktopUiAction.TriggerUpdate)
                }
            }

            Divider()

            Item(label = quitLabel) {
                quitApplication()
            }
        }

        val place by WindowTitle.place.collectAsStateWithLifecycle(lifecycleOwner = appLifecycleOwner)
        Window(
            onCloseRequest = hideWindow,
            alwaysOnTop = false,
            title = WindowTitle.of(place, appName),
            icon = icon,
            state = state,
            visible = isWindowVisible
        ) {
            SmokeExit()
            MenuBar {
                Menu(stringResource(MR.string.desktop_menu_file)) {
                    if (uiState.updateAvailable) {
                        Item(
                            text = updateAvailable,
                            onClick = { viewModel.onUiAction(DesktopUiAction.TriggerUpdate) },
                        )
                        Separator()
                    }
                    Item(
                        text = stringResource(MR.string.chat_new_conversation),
                        enabled = canNavigate,
                        shortcut = menuShortcut(Key.N),
                        onClick = { navigateTo?.invoke(Route.CreateConversation) },
                    )
                    Separator()
                    Item(
                        text = stringResource(MR.string.desktop_menu_close_window),
                        shortcut = menuShortcut(Key.W),
                        onClick = hideWindow,
                    )
                }
                if (callRinging) {
                    Menu(stringResource(MR.string.desktop_menu_call)) {
                        Item(
                            text = stringResource(MR.string.desktop_menu_call_answer),
                            shortcut = menuShortcut(Key.A, shift = true),
                            onClick = callWindow::answer,
                        )
                        Item(
                            text = stringResource(MR.string.desktop_menu_call_decline),
                            shortcut = menuShortcut(Key.H, shift = true),
                            onClick = callWindow::decline,
                        )
                    }
                }
                Menu(themeMenuLabel) {
                    ThemeState.entries.forEach { theme ->
                        RadioButtonItem(
                            text = theme.getStringResourceForTheme(),
                            selected = uiState.theme == theme,
                            onClick = { viewModel.onUiAction(DesktopUiAction.SetTheme(theme)) },
                        )
                    }
                }
            }

            DesktopAppFocusManager.registerWindowProvider { window }
            window.minimumSize = java.awt.Dimension(minWidth, minHeight)
            LaunchedEffect(window) {
                Logger.i(tag = "DesktopWindowState") {
                    "window created: renderApi=${window.renderApi} scale=${window.graphicsConfiguration.defaultTransform.scaleX}"
                }
            }

            // Diagnostic: an un-minimise nobody asked for has no other trace, so log the
            // transition to correlate it against the requestFocus / notification lines.
            DisposableEffect(window) {
                val listener = WindowStateListener { event ->
                    val wasIconified = (event.oldState and Frame.ICONIFIED) != 0
                    val isIconified = (event.newState and Frame.ICONIFIED) != 0
                    if (wasIconified != isIconified) {
                        Logger.i(tag = "DesktopWindowState") {
                            if (isIconified) "window minimised" else "window restored (de-iconified)"
                        }
                    }
                }
                window.addWindowStateListener(listener)
                onDispose { window.removeWindowStateListener(listener) }
            }

            LaunchedEffect(window) { AppIconBadge.start(window) }

            if (isMacOs) {
                LaunchedEffect(Unit) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }

            // Provide Desktop-specific LifecycleOwner to the composition tree
            val desktopLifecycleOwner = rememberDesktopLifecycleOwner(isWindowVisible)
            CompositionLocalProvider(LocalLifecycleOwner provides desktopLifecycleOwner) {
                App(
                    onNavigatorReady = { navigateTo = it },
                    topInset = if (isMacOs) MAC_TITLE_BAR_HEIGHT else 0.dp,
                    hostCallOverlay = false,
                )
            }
        }
    }
}

internal val isMacOs = hostOs == HostOs.Mac

// Traffic lights float over the content once apple.awt.fullWindowContent is on.
private val MAC_TITLE_BAR_HEIGHT = 28.dp

internal fun menuShortcut(key: Key, shift: Boolean = false) =
    KeyShortcut(key, ctrl = !isMacOs, meta = isMacOs, shift = shift)

/**
 * A failure the desktop app records and keeps running for, instead of dying: a transient network
 * blip (PR #737), a recoverable optimistic-concurrency conflict (400 VersionTagMismatch, #1008),
 * or a permission denial (403 — the app token's grant was revoked or narrowed server-side).
 *
 * All three are server answers, not client defects, and all three routinely leak from a coroutine
 * launched on a scope with no CoroutineExceptionHandler of its own (a bare `viewModelScope.launch`
 * — how every add-on activates its drive). Anything else still terminates and reports. Pure so it
 * is unit-testable; mirrors Android's `GlobalCrashHandler.isContainableNonFatal`.
 */
internal fun isContainableNonFatal(throwable: Throwable): Boolean =
    throwable.isTransientNetworkFailure() ||
        throwable.isRecoverableServerConflict() ||
        throwable.isRecoverablePermissionFailure()

// Reset clears secure storage only; the open that follows hits the old encrypted file and initializeWithRecovery wipes it.
private fun openDatabaseOrQuit() {
    while (!runBlocking { AppStartup.openDatabase(DatabaseDriverFactory()) }) {
        when (askKeystoreChoice()) {
            KeystoreChoice.Retry -> Unit
            KeystoreChoice.Reset -> SecureStorage.clear()
            KeystoreChoice.Quit -> exitProcess(1)
        }
    }
}

private enum class KeystoreChoice { Retry, Reset, Quit }

private fun askKeystoreChoice(): KeystoreChoice {
    val text = runBlocking {
        listOf(
            MR.string.keystore_unavailable_title, MR.string.keystore_unavailable_message,
            MR.string.keystore_unavailable_retry, MR.string.keystore_unavailable_reset, MR.string.keystore_unavailable_quit,
            MR.string.keystore_reset_confirm_title, MR.string.keystore_reset_confirm_message,
            MR.string.keystore_reset_confirm, MR.string.cancel,
        ).map { getString(it) }
    }
    val (title, message, retry, reset, quit) = text
    val (confirmTitle, confirmMessage, confirm, cancel) = text.drop(5)
    while (true) {
        when (
            JOptionPane.showOptionDialog(
                null, message, title, JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE,
                null, arrayOf(retry, reset, quit), retry,
            )
        ) {
            0 -> return KeystoreChoice.Retry
            1 -> if (
                JOptionPane.showOptionDialog(
                    null, confirmMessage, confirmTitle, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, arrayOf(confirm, cancel), cancel,
                ) == 0
            ) return KeystoreChoice.Reset
            else -> return KeystoreChoice.Quit
        }
    }
}

private fun setupCrashHandler() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        if (isContainableNonFatal(throwable)) {
            crashlyticsRecordException(throwable)
            Logger.w(tag = "CrashHandler") {
                val kind = when {
                    throwable.isRecoverableServerConflict() ->
                        "Recoverable server conflict (stale versionTag; write dropped, drive-sync reconciles)"
                    throwable.isRecoverablePermissionFailure() ->
                        "Permission denied by the server (403; the grant was revoked or narrowed)"
                    else -> "Transient network failure"
                }
                "$kind leaked to '${thread.name}' (no local handler); " +
                    "app not crashing: ${throwable.message}\n" +
                    throwable.stackTraceToString()
            }
            return@setDefaultUncaughtExceptionHandler
        }
        try {
            CrashLogger.logCrash(thread.name, throwable)
            failSmokeRun(throwable)
            val reportPath = CrashReporting.writeReport(thread.name, throwable)
            // Modal dialog; it handles Restart/Reveal/Quit and owns termination.
            CrashRecoveryDialog.showAndBlock(reportPath?.toString())
        } catch (e: Throwable) {
            e.printStackTrace()
            exitProcess(1)
        }
    }
}
