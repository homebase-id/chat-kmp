package id.homebase.chat.calling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.CallPhase
import id.homebase.resources.MR
import id.homebase.resources.call_key_escape
import id.homebase.resources.call_shortcuts_camera
import id.homebase.resources.call_shortcuts_done
import id.homebase.resources.call_shortcuts_end
import id.homebase.resources.call_shortcuts_help
import id.homebase.resources.call_shortcuts_mini
import id.homebase.resources.call_shortcuts_mute
import id.homebase.resources.call_shortcuts_title
import org.jetbrains.compose.resources.stringResource

enum class CallShortcut { ToggleMute, ToggleCamera, ToggleMini, Close, ShowShortcuts }

/** The slice of a key event the shortcut map reads; [command] is Cmd on macOS and Ctrl elsewhere, either is accepted. */
data class CallKeyInput(
    val key: Key,
    val type: KeyEventType,
    val command: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val codePoint: Int = 0,
)

fun KeyEvent.toCallKeyInput() =
    CallKeyInput(key, type, command = isMetaPressed || isCtrlPressed, shift = isShiftPressed, alt = isAltPressed, codePoint = utf16CodePoint)

/** [textFocused] frees the bare letter keys and `?` for typing; chorded shortcuts and Esc still fire. */
fun callShortcutFor(input: CallKeyInput, ui: CallUiState, textFocused: Boolean = false): CallShortcut? {
    if (input.type != KeyEventType.KeyDown) return null
    // The outgoing ring already shows mute and camera, so their keys must work there too.
    val live = ui.inProgress || ui.phase == CallPhase.Outgoing
    val cameraAvailable = ui.videos.local != null
    val key = input.key
    if (key == Key.Escape) return CallShortcut.Close
    if (input.command) {
        if (input.alt) return null
        return when {
            key == Key.W -> CallShortcut.Close
            key == Key.D && !input.shift && live -> CallShortcut.ToggleMute
            key == Key.E && !input.shift && live && cameraAvailable -> CallShortcut.ToggleCamera
            key == Key.M && input.shift && live -> CallShortcut.ToggleMini
            else -> null
        }
    }
    if (input.alt || textFocused) return null
    if (input.shift) {
        return if (key == Key.Slash || input.codePoint == '?'.code) CallShortcut.ShowShortcuts else null
    }
    return when {
        key == Key.M && live -> CallShortcut.ToggleMute
        key == Key.V && live && cameraAvailable -> CallShortcut.ToggleCamera
        else -> null
    }
}

/** "⌘⇧M" on macOS, "Ctrl+Shift+M" elsewhere. */
fun callShortcutKeys(mac: Boolean, key: String, shift: Boolean = false): String =
    if (mac) "⌘" + (if (shift) "⇧" else "") + key else "Ctrl+" + (if (shift) "Shift+" else "") + key

val LocalMacShortcuts = staticCompositionLocalOf { false }

internal val LocalCallTextFocus = staticCompositionLocalOf { mutableStateOf(false) }

/** Mark any text field added to the call window with this so the bare-letter shortcuts stay out of its way. */
@Composable
internal fun Modifier.callTextField(): Modifier {
    val focus = LocalCallTextFocus.current
    return onFocusChanged { focus.value = it.isFocused }
}

internal const val ChromeIdleMs = 3_000L

/** Video chrome only fades once the call is up; ringing, connecting, reconnecting and ended screens carry state the user must see. */
internal fun chromeAutoHideEligible(ui: CallUiState): Boolean = ui.isVideoCall && ui.phase == CallPhase.Connected

/** Counts open tooltips and menus; the chrome stays put while any is up. */
internal class ChromeHold {
    var count by mutableIntStateOf(0)
}

internal val LocalChromeHold = staticCompositionLocalOf<ChromeHold?> { null }
internal val LocalChromeVisible = staticCompositionLocalOf { true }

@Composable
internal fun HoldChromeWhile(active: Boolean) {
    val hold = LocalChromeHold.current ?: return
    DisposableEffect(active) {
        if (active) hold.count++
        onDispose { if (active) hold.count-- }
    }
}

@Composable
internal fun CallShortcutsDialog(onDismiss: () -> Unit) {
    val mac = LocalMacShortcuts.current
    val rows = listOf(
        stringResource(MR.string.call_shortcuts_mute) to "${callShortcutKeys(mac, "D")}  /  M",
        stringResource(MR.string.call_shortcuts_camera) to "${callShortcutKeys(mac, "E")}  /  V",
        stringResource(MR.string.call_shortcuts_mini) to callShortcutKeys(mac, "M", shift = true),
        stringResource(MR.string.call_shortcuts_end) to "${stringResource(MR.string.call_key_escape)}  /  ${callShortcutKeys(mac, "W")}",
        stringResource(MR.string.call_shortcuts_help) to "?",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.call_shortcuts_title)) },
        text = {
            Column(Modifier.widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { (label, keys) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Text(keys, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.string.call_shortcuts_done)) } },
    )
}
