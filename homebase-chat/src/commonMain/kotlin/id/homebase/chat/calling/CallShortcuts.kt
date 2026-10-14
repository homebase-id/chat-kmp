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
import id.homebase.resources.call_key_cmd_combo
import id.homebase.resources.call_key_cmd_shift_combo
import id.homebase.resources.call_key_ctrl_combo
import id.homebase.resources.call_key_ctrl_shift_combo
import id.homebase.resources.call_key_escape
import id.homebase.resources.call_key_help
import id.homebase.resources.call_shortcuts_camera
import id.homebase.resources.call_shortcuts_done
import id.homebase.resources.call_shortcuts_either
import id.homebase.resources.call_shortcuts_end
import id.homebase.resources.call_shortcuts_hang_up
import id.homebase.resources.call_shortcuts_help
import id.homebase.resources.call_shortcuts_mini
import id.homebase.resources.call_shortcuts_mute
import id.homebase.resources.call_shortcuts_title
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

enum class CallShortcut { ToggleMute, ToggleCamera, ToggleMini, HangUp, Close, ShowShortcuts }

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

fun callIsLive(ui: CallUiState): Boolean = ui.inProgress || ui.phase == CallPhase.Outgoing

fun callCameraToggleAvailable(ui: CallUiState): Boolean = callIsLive(ui) && ui.videos.local != null

fun callShortcutFor(input: CallKeyInput, ui: CallUiState, textFocused: Boolean = false): CallShortcut? {
    if (input.type != KeyEventType.KeyDown) return null
    val live = callIsLive(ui)
    val cameraAvailable = ui.videos.local != null
    val key = input.key
    if (key == Key.Escape) return CallShortcut.Close
    if (input.command) {
        if (input.alt) return null
        return when {
            key == Key.W -> CallShortcut.Close
            key == Key.H && input.shift && live -> CallShortcut.HangUp
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

@Composable
fun callShortcutKeys(key: String, shift: Boolean = false): String {
    val res = when {
        LocalMacShortcuts.current -> if (shift) MR.string.call_key_cmd_shift_combo else MR.string.call_key_cmd_combo
        else -> if (shift) MR.string.call_key_ctrl_shift_combo else MR.string.call_key_ctrl_combo
    }
    return stringResource(res, key)
}

val LocalMacShortcuts = staticCompositionLocalOf { false }

class ShortcutGate(private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private var last: CallShortcut? = null
    private var at = 0L

    fun accept(shortcut: CallShortcut): Boolean {
        val now = nowMs()
        if (shortcut == last && now - at < DuplicateWindowMs) return false
        last = shortcut
        at = now
        return true
    }

    private companion object {
        const val DuplicateWindowMs = 150L
    }
}

val LocalShortcutGate = staticCompositionLocalOf<ShortcutGate?> { null }

internal fun chromeAutoHideEligible(ui: CallUiState): Boolean = ui.isVideoCall && ui.phase == CallPhase.Connected

class ChromeHold {
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
    val rows = listOf(
        stringResource(MR.string.call_shortcuts_mute) to stringResource(MR.string.call_shortcuts_either, callShortcutKeys("D"), "M"),
        stringResource(MR.string.call_shortcuts_camera) to stringResource(MR.string.call_shortcuts_either, callShortcutKeys("E"), "V"),
        stringResource(MR.string.call_shortcuts_mini) to callShortcutKeys("M", shift = true),
        stringResource(MR.string.call_shortcuts_hang_up) to callShortcutKeys("H", shift = true),
        stringResource(MR.string.call_shortcuts_end) to stringResource(MR.string.call_shortcuts_either, stringResource(MR.string.call_key_escape), callShortcutKeys("W")),
        stringResource(MR.string.call_shortcuts_help) to stringResource(MR.string.call_key_help),
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
