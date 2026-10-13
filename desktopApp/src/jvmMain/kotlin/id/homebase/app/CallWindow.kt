package id.homebase.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.app.lifecycle.rememberDesktopLifecycleOwner
import id.homebase.core.calling.DesktopCallWindow
import id.homebase.core.settings.ThemeState
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.desktop_menu_close_window
import id.homebase.resources.desktop_menu_file
import id.homebase.resources.homebase_icon_round
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal const val CALL_WINDOW_FOCUS_KEY = "call"
private val MAC_CALL_TITLE_BAR_HEIGHT = 28.dp

@Composable
fun ApplicationScope.CallWindow(call: DesktopCallWindow, config: DesktopPreferences, theme: ThemeState) {
    val visible by call.visible.collectAsState()
    if (!visible) return
    val ringing by call.ringing.collectAsState()
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
            DesktopAppFocusManager.registerWindowProvider(CALL_WINDOW_FOCUS_KEY) { window }
            onDispose { DesktopAppFocusManager.unregisterWindowProvider(CALL_WINDOW_FOCUS_KEY) }
        }
        MenuBar {
            Menu(stringResource(MR.string.desktop_menu_file)) {
                Item(
                    text = stringResource(MR.string.desktop_menu_close_window),
                    shortcut = KeyShortcut(Key.W, ctrl = !isMacOs, meta = isMacOs),
                    onClick = call::requestClose,
                )
            }
        }
        if (isMacOs) {
            LaunchedEffect(Unit) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
        }
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
            ) {
                call.Content(
                    onMinimize = { window.extendedState = window.extendedState or java.awt.Frame.ICONIFIED },
                    topInset = if (isMacOs) MAC_CALL_TITLE_BAR_HEIGHT else 0.dp,
                )
            }
        }
    }
}
