package id.homebase.chat.widget

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.core.util.isImeComposing

/** What a key press means to a composer field. */
enum class ComposerKeyAction {
    Send,
    Newline,

    /** Not ours — the field, the platform, or a later handler gets the key. */
    Ignore,
}

internal data class ComposerEnterChord(
    val isEnter: Boolean,
    val isKeyDown: Boolean,
    val isShiftPressed: Boolean,
    val isSendModifierPressed: Boolean,
    val isImeComposing: Boolean,
)

internal fun KeyEvent.toEnterChord(): ComposerEnterChord = ComposerEnterChord(
    // macOS reports Return as NumPadEnter, so Key.Enter alone misses it there.
    isEnter = key == Key.Enter || key == Key.NumPadEnter,
    isKeyDown = type == KeyEventType.KeyDown,
    isShiftPressed = isShiftPressed,
    isSendModifierPressed = isCtrlPressed || isMetaPressed,
    isImeComposing = isImeComposing(),
)

/**
 * The one place that decides whether a key press sends the message or breaks the line. Every
 * composer field routes through it, after the autocomplete list has had its refusal.
 */
fun composerKeyAction(event: KeyEvent, enterSendsMessage: Boolean): ComposerKeyAction =
    composerKeyAction(
        chord = event.toEnterChord(),
        enterSendsMessage = enterSendsMessage,
        // Mobile leaves Enter to the IME, which inserts the newline itself.
        handlesHardwareEnter = isDesktopOrWeb(),
    )

internal fun composerKeyAction(
    chord: ComposerEnterChord,
    enterSendsMessage: Boolean,
    handlesHardwareEnter: Boolean,
): ComposerKeyAction {
    if (!chord.isKeyDown || !chord.isEnter || !handlesHardwareEnter) return ComposerKeyAction.Ignore
    // An Enter that is confirming an IME candidate belongs to the IME, in either mode.
    if (chord.isImeComposing) return ComposerKeyAction.Ignore
    if (chord.isSendModifierPressed) return ComposerKeyAction.Send

    val sends = if (chord.isShiftPressed) !enterSendsMessage else enterSendsMessage
    return if (sends) ComposerKeyAction.Send else ComposerKeyAction.Newline
}
