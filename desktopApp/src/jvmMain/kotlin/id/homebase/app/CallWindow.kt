package id.homebase.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import id.homebase.core.calling.DesktopRingWindowSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.browser.WindowKeys
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
import id.homebase.resources.desktop_menu_call_unmute
import id.homebase.resources.desktop_menu_file
import id.homebase.resources.homebase_icon_round
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val MAC_CALL_TITLE_BAR_HEIGHT = 28.dp

@Composable
fun ApplicationScope.CallWindow(call: DesktopCallWindow, config: DesktopPreferences, theme: ThemeState) {
    // application{} provides no LocalLifecycleOwner, and this scope sits above any Window.
    CompositionLocalProvider(LocalLifecycleOwner provides rememberApplicationLifecycleOwner()) {
        val visible by call.visible.collectAsStateWithLifecycle()
        val callKey by call.windowKey.collectAsStateWithLifecycle()
        if (visible) {
            key(callKey) { CallWindowFrame(call, config, theme) }
        }
    }
}

internal class CallWindowModes(val full: WindowState) {
    var mini by mutableStateOf<MiniEntry?>(null)
        private set

    fun enterMini(focusable: Boolean) {
        mini = MiniEntry(focusable)
    }

    fun exitMini() {
        mini = null
    }
}

internal data class MiniEntry(val focusable: Boolean)

@Composable
private fun ApplicationScope.CallWindowFrame(call: DesktopCallWindow, config: DesktopPreferences, theme: ThemeState) {
    val video = remember { call.isVideoCall() }
    val geometry = remember { config.callWindowGeometry(video) }
    val full = rememberWindowState(position = geometry.position, width = geometry.width, height = geometry.height)
    val modes = remember { CallWindowModes(full) }
    val live by call.live.collectAsStateWithLifecycle()
    LaunchedEffect(live) { if (!live) modes.exitMini() }
    val mini = modes.mini
    DisposableEffect(mini != null) {
        call.setMiniWindowOpen(mini != null)
        onDispose { call.setMiniWindowOpen(false) }
    }
    val ringing by call.ringing.collectAsStateWithLifecycle()
    if (ringing) {
        RingCallWindow(call, theme)
        return
    }
    key(mini) {
        if (mini != null) MiniCallWindow(call, config, theme, video, mini, modes::exitMini)
        else FullCallWindow(call, config, theme, video, full, modes::enterMini)
    }
}

private val RING_WINDOW_MARGIN = 16.dp

internal fun ringWindowPosition(area: DpRect, size: DpSize, margin: Dp = RING_WINDOW_MARGIN): WindowPosition.Absolute =
    WindowPosition.Absolute(area.right - size.width - margin, area.top + margin)

@Composable
private fun ApplicationScope.RingCallWindow(call: DesktopCallWindow, theme: ThemeState) {
    val size = DesktopRingWindowSize
    val state = rememberWindowState(position = remember { ringWindowPosition(activeScreenArea(), size) }, size = size)
    Window(
        onCloseRequest = call::requestClose,
        state = state,
        title = call.title(),
        icon = painterResource(MR.drawable.homebase_icon_round),
        undecorated = true,
        transparent = isMacOs,
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
    ) {
        RegisterCallWindow()
        CallWindowTheme(theme) { call.RingContent(Modifier) }
    }
}

private fun activeScreenArea(): DpRect {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val pointer = MouseInfo.getPointerInfo()?.location
    val config = env.screenDevices.map { it.defaultConfiguration }.firstOrNull { pointer != null && it.bounds.contains(pointer) }
        ?: env.defaultScreenDevice.defaultConfiguration
    val bounds = config.bounds
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(config)
    return DpRect(
        (bounds.x + insets.left).dp, (bounds.y + insets.top).dp,
        (bounds.x + bounds.width - insets.right).dp, (bounds.y + bounds.height - insets.bottom).dp,
    )
}

@Composable
private fun FrameWindowScope.CallMenuBar(call: DesktopCallWindow, config: DesktopPreferences, close: () -> Unit, toggleMini: () -> Unit) {
    val status = collectSettledCallEntries(call.trayItems, LocalLifecycleOwner.current).firstOrNull { it.action == CallMenuAction.OpenCall }
    val live by call.live.collectAsStateWithLifecycle()
    val muted by call.muted.collectAsStateWithLifecycle()
    val canToggleCamera by call.canToggleCamera.collectAsStateWithLifecycle()
    var autoMini by remember { mutableStateOf(config.callAutoMini) }
    MenuBar {
        Menu(stringResource(MR.string.desktop_menu_file)) {
            Item(
                text = stringResource(MR.string.desktop_menu_close_window),
                shortcut = menuShortcut(Key.W),
                onClick = close,
            )
        }
        Menu(stringResource(MR.string.desktop_menu_call)) {
            if (status != null) {
                Item(text = status.label, enabled = false, onClick = {})
                Separator()
            }
            Item(
                text = stringResource(if (muted) MR.string.desktop_menu_call_unmute else MR.string.desktop_menu_call_mute),
                shortcut = menuShortcut(Key.D),
                enabled = live,
                onClick = call::menuToggleMute,
            )
            Item(
                text = stringResource(MR.string.desktop_menu_call_camera),
                shortcut = menuShortcut(Key.E),
                enabled = canToggleCamera,
                onClick = call::menuToggleVideo,
            )
            Item(
                text = stringResource(MR.string.desktop_menu_call_mini),
                shortcut = menuShortcut(Key.M, shift = true),
                enabled = live,
                onClick = { call.menuToggleMini(toggleMini) },
            )
            Item(
                text = stringResource(MR.string.desktop_menu_call_hang_up),
                shortcut = menuShortcut(Key.H, shift = true),
                enabled = live,
                onClick = call::menuHangup,
            )
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
private fun ApplicationScope.FullCallWindow(
    call: DesktopCallWindow,
    config: DesktopPreferences,
    theme: ThemeState,
    video: Boolean,
    state: WindowState,
    enterMini: (focusable: Boolean) -> Unit,
) {
    val saveGeometry = {
        if (state.placement == WindowPlacement.Floating) {
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
    ) {
        window.minimumSize = java.awt.Dimension(CALL_WINDOW_MIN_WIDTH.toInt(), CALL_WINDOW_MIN_HEIGHT.toInt())
        RegisterCallWindow()
        DisposableEffect(window) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent) = Unit

                override fun windowLostFocus(e: WindowEvent) {
                    val main = DesktopAppFocusManager.windowFor(WindowKeys.MAIN)
                    if (config.callAutoMini && call.live.value && main != null && e.oppositeWindow === main) {
                        SwingUtilities.invokeLater { enterMini(false) }
                    }
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        CallMenuBar(call, config, close = call::requestClose, toggleMini = { enterMini(true) })
        if (isMacOs) {
            LaunchedEffect(Unit) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
        }
        CallWindowTheme(theme) {
            call.Content(
                onMinimize = ::focusChat,
                topInset = if (isMacOs) MAC_CALL_TITLE_BAR_HEIGHT else 0.dp,
                mini = false,
                onToggleMini = { enterMini(true) },
                onMiniDragStart = {},
                onMiniDrag = {},
            )
        }
    }
}

@Composable
private fun ApplicationScope.MiniCallWindow(
    call: DesktopCallWindow,
    config: DesktopPreferences,
    theme: ThemeState,
    video: Boolean,
    entry: MiniEntry,
    exitMini: () -> Unit,
) {
    val size = remember { miniCallSize(video) }
    val state = rememberWindowState(
        position = remember { miniCallPosition(config.miniWindowPosition(), size, screenBounds()) },
        size = size,
    )
    Window(
        onCloseRequest = exitMini,
        state = state,
        title = call.title(),
        icon = painterResource(MR.drawable.homebase_icon_round),
        undecorated = true,
        // The mini content draws its own rounded corners on macOS; elsewhere the frame stays square and opaque.
        transparent = isMacOs,
        resizable = false,
        focusable = entry.focusable,
        alwaysOnTop = true,
    ) {
        RegisterCallWindow()
        DisposableEffect(window) {
            onDispose { runCatching { config.saveMiniWindowPosition(WindowPosition.Absolute(window.x.dp, window.y.dp)) } }
        }
        // A JMenuBar would be drawn inside this undecorated frame on Windows and Linux.
        if (isMacOs) CallMenuBar(call, config, close = call::requestClose, toggleMini = exitMini)
        var dragOrigin by remember { mutableStateOf<Pair<Point, Point>?>(null) }
        CallWindowTheme(theme) {
            call.Content(
                onMinimize = ::focusChat,
                topInset = 0.dp,
                mini = true,
                onToggleMini = exitMini,
                onMiniDragStart = { dragOrigin = MouseInfo.getPointerInfo().location to window.location },
                onMiniDrag = {
                    dragOrigin?.let { (pointer, origin) ->
                        val now = MouseInfo.getPointerInfo().location
                        window.setLocation(origin.x + now.x - pointer.x, origin.y + now.y - pointer.y)
                    }
                },
            )
        }
    }
}

private fun focusChat() = DesktopAppFocusManager.requestFocus("call-back-to-chat")

@Composable
private fun FrameWindowScope.RegisterCallWindow() {
    DisposableEffect(window) {
        DesktopAppFocusManager.registerWindowProvider(WindowKeys.CALL) { window }
        onDispose { DesktopAppFocusManager.unregisterWindowProvider(WindowKeys.CALL) }
    }
}

private fun screenBounds(): List<DpRect> =
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        device.defaultConfiguration.bounds.let { DpRect(it.x.dp, it.y.dp, (it.x + it.width).dp, (it.y + it.height).dp) }
    }

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
