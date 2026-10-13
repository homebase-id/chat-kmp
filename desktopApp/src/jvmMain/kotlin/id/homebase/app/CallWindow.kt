package id.homebase.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPosition
import java.awt.Toolkit
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.browser.WindowKeys
import id.homebase.app.lifecycle.DesktopLifecycleOwner
import id.homebase.app.lifecycle.rememberDesktopLifecycleOwner
import id.homebase.core.calling.DesktopCallWindow
import id.homebase.core.settings.ThemeState
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.desktop_menu_close_window
import id.homebase.resources.desktop_menu_call
import id.homebase.resources.desktop_menu_call_auto_mini
import id.homebase.resources.desktop_menu_call_camera
import id.homebase.resources.desktop_menu_call_hang_up
import id.homebase.resources.desktop_menu_call_mini
import id.homebase.resources.desktop_menu_call_mute
import id.homebase.resources.desktop_menu_file
import id.homebase.resources.homebase_icon_round
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val MAC_CALL_TITLE_BAR_HEIGHT = 28.dp

@Composable
fun ApplicationScope.CallWindow(call: DesktopCallWindow, config: DesktopPreferences, theme: ThemeState) {
    // application{} provides no LocalLifecycleOwner, and the call flows and the window title are
    // collected here, above any Window, so this scope needs an owner of its own.
    val applicationOwner = remember { DesktopLifecycleOwner().also { it.onWindowVisible() } }
    DisposableEffect(applicationOwner) { onDispose { applicationOwner.onWindowClosed() } }
    CompositionLocalProvider(LocalLifecycleOwner provides applicationOwner) {
        val visible by call.visible.collectAsStateWithLifecycle()
        val callKey by call.windowKey.collectAsStateWithLifecycle()
        if (visible) {
            key(callKey) { CallWindowFrame(call, config, theme) }
        }
    }
}

@Composable
private fun ApplicationScope.CallWindowFrame(call: DesktopCallWindow, config: DesktopPreferences, theme: ThemeState) {
    var mini by remember { mutableStateOf(false) }
    val live by call.live.collectAsStateWithLifecycle()
    // A finished call has nothing to pin; the ended screen belongs in the full window.
    LaunchedEffect(live) { if (!live) mini = false }
    key(mini) {
        if (mini) MiniCallWindow(call, config, theme) { mini = false }
        else FullCallWindow(call, config, theme) { mini = true }
    }
}

@Composable
private fun FrameWindowScope.CallMenuBar(call: DesktopCallWindow, config: DesktopPreferences, close: () -> Unit, toggleMini: () -> Unit) {
    val live by call.live.collectAsStateWithLifecycle()
    var autoMini by remember { mutableStateOf(config.callAutoMini) }
    MenuBar {
        Menu(stringResource(MR.string.desktop_menu_file)) {
            Item(
                text = stringResource(MR.string.desktop_menu_close_window),
                shortcut = KeyShortcut(Key.W, ctrl = !isMacOs, meta = isMacOs),
                onClick = close,
            )
        }
        Menu(stringResource(MR.string.desktop_menu_call)) {
            Item(
                text = stringResource(MR.string.desktop_menu_call_mute),
                shortcut = KeyShortcut(Key.D, ctrl = !isMacOs, meta = isMacOs),
                enabled = live,
                onClick = call::toggleMute,
            )
            Item(
                text = stringResource(MR.string.desktop_menu_call_camera),
                shortcut = KeyShortcut(Key.E, ctrl = !isMacOs, meta = isMacOs),
                enabled = live,
                onClick = call::toggleVideo,
            )
            Item(
                text = stringResource(MR.string.desktop_menu_call_mini),
                shortcut = KeyShortcut(Key.M, ctrl = !isMacOs, meta = isMacOs, shift = true),
                enabled = live,
                onClick = toggleMini,
            )
            Item(text = stringResource(MR.string.desktop_menu_call_hang_up), enabled = live, onClick = call::hangup)
            Separator()
            CheckboxItem(
                text = stringResource(MR.string.desktop_menu_call_auto_mini),
                checked = autoMini,
                onCheckedChange = {
                    autoMini = it
                    config.callAutoMini = it
                },
            )
        }
    }
}

@Composable
private fun ApplicationScope.FullCallWindow(call: DesktopCallWindow, config: DesktopPreferences, theme: ThemeState, enterMini: () -> Unit) {
    val ringing by call.ringing.collectAsStateWithLifecycle()
    val video = remember { call.isVideoCall() }
    val geometry = remember { config.callWindowGeometry(video) }
    val state = rememberWindowState(position = geometry.position, width = geometry.width, height = geometry.height)
    val saveGeometry = {
        runCatching {
            config.saveCallWindowGeometry(
                video,
                CallWindowGeometry(
                    maxOf(state.size.width, CALL_WINDOW_MIN_WIDTH.dp),
                    maxOf(state.size.height, CALL_WINDOW_MIN_HEIGHT.dp),
                    state.position,
                ),
            )
        }
    }
    DisposableEffect(Unit) { onDispose { saveGeometry() } }

    Window(
        onCloseRequest = {
            saveGeometry()
            call.requestClose()
        },
        state = state,
        title = call.title(),
        icon = painterResource(MR.drawable.homebase_icon_round),
        alwaysOnTop = ringing,
    ) {
        window.minimumSize = java.awt.Dimension(CALL_WINDOW_MIN_WIDTH.toInt(), CALL_WINDOW_MIN_HEIGHT.toInt())
        DisposableEffect(window) {
            DesktopAppFocusManager.registerWindowProvider(WindowKeys.CALL) { window }
            onDispose { DesktopAppFocusManager.unregisterWindowProvider(WindowKeys.CALL) }
        }
        DisposableEffect(window) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent) = Unit

                override fun windowLostFocus(e: WindowEvent) {
                    val main = DesktopAppFocusManager.windowFor(WindowKeys.MAIN)
                    if (config.callAutoMini && call.live.value && main != null && e.oppositeWindow === main) {
                        SwingUtilities.invokeLater { enterMini() }
                    }
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        CallMenuBar(call, config, close = call::requestClose, toggleMini = enterMini)
        if (isMacOs) {
            LaunchedEffect(Unit) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
        }
        CallWindowTheme(theme) {
            call.Content(
                onMinimize = { DesktopAppFocusManager.requestFocus("call-back-to-chat") },
                topInset = if (isMacOs) MAC_CALL_TITLE_BAR_HEIGHT else 0.dp,
                mini = false,
                onToggleMini = enterMini,
            )
        }
    }
}

@Composable
private fun ApplicationScope.MiniCallWindow(call: DesktopCallWindow, config: DesktopPreferences, theme: ThemeState, exitMini: () -> Unit) {
    val video = remember { call.isVideoCall() }
    val size = remember { miniCallSize(video) }
    val state = rememberWindowState(
        position = remember { miniCallPosition(config.miniWindowPosition(), size, screenSize()) },
        size = size,
    )
    DisposableEffect(Unit) {
        onDispose { (state.position as? WindowPosition.Absolute)?.let { runCatching { config.saveMiniWindowPosition(it) } } }
    }
    Window(
        onCloseRequest = exitMini,
        state = state,
        title = call.title(),
        icon = painterResource(MR.drawable.homebase_icon_round),
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
    ) {
        DisposableEffect(window) {
            DesktopAppFocusManager.registerWindowProvider(WindowKeys.CALL) { window }
            onDispose { DesktopAppFocusManager.unregisterWindowProvider(WindowKeys.CALL) }
        }
        // Esc and Cmd+W only shrink back: the end-call confirmation lives in the full window.
        CallMenuBar(call, config, close = exitMini, toggleMini = exitMini)
        CallWindowTheme(theme) {
            WindowDraggableArea {
                call.Content(
                    onMinimize = { DesktopAppFocusManager.requestFocus("call-back-to-chat") },
                    topInset = 0.dp,
                    mini = true,
                    onToggleMini = exitMini,
                )
            }
        }
    }
}

private fun screenSize(): DpSize =
    Toolkit.getDefaultToolkit().screenSize.let { DpSize(it.width.dp, it.height.dp) }

@Composable
private fun FrameWindowScope.CallWindowTheme(theme: ThemeState, content: @Composable () -> Unit) {
    val lifecycleOwner = rememberDesktopLifecycleOwner(true)
    CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        HomebaseTheme(
            darkTheme = when (theme) {
                ThemeState.System -> isSystemInDarkTheme()
                ThemeState.Dark -> true
                else -> false
            },
            followsSystemTheme = theme == ThemeState.System,
            updatesSystemChrome = false,
            content = content,
        )
    }
}
