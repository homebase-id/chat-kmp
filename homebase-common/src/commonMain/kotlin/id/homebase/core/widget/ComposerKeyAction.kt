package id.homebase.core.widget

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

enum class ComposerKeyAction {
    Send,
    Newline,
    Ignore,
}

internal class ComposerEnterChord(
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

// Call after autocomplete.handleKeyEvent — it claims Enter first.
fun composerKeyAction(event: KeyEvent, enterSendsMessage: Boolean): ComposerKeyAction =
    composerKeyAction(
        chord = event.toEnterChord(),
        enterSendsMessage = enterSendsMessage,
        // Mobile leaves Enter to the IME, which inserts the newline itself.
        handlesHardwareEnter = isDesktopOrWeb(),
    )

/** An Enter the composer decides on: it sends or breaks the line, whatever else is showing. */
internal fun KeyEvent.isModifiedEnter(): Boolean = with(toEnterChord()) {
    isEnter && isKeyDown && (isShiftPressed || isSendModifierPressed)
}

internal fun composerKeyAction(
    chord: ComposerEnterChord,
    enterSendsMessage: Boolean,
    handlesHardwareEnter: Boolean,
): ComposerKeyAction {
    if (!chord.isKeyDown || !chord.isEnter || !handlesHardwareEnter) return ComposerKeyAction.Ignore
    // In either mode: an IME's Enter confirms its own candidate.
    if (chord.isImeComposing) return ComposerKeyAction.Ignore
    if (chord.isSendModifierPressed) return ComposerKeyAction.Send

    return if (chord.isShiftPressed != enterSendsMessage) ComposerKeyAction.Send
    else ComposerKeyAction.Newline
}
