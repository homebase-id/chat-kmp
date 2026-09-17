package id.homebase.core.widget

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import id.homebase.core.clipboard.getImageFromClipboard
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.core.util.isImeComposing

fun Modifier.composerKeyHandler(
    autocomplete: ComposerAutocompleteController? = null,
    enterSendsMessage: Boolean,
    onSend: () -> Unit,
    onNewline: (() -> Unit)? = null,
    onPasteImage: ((ByteArray) -> Unit)? = null,
): Modifier = onPreviewKeyEvent { event ->
    handleComposerKey(
        isImeComposing = event.isImeComposing(),
        claimedByAutocomplete = { autocomplete?.handleKeyEvent(event) == true },
        action = composerKeyAction(event, enterSendsMessage),
        onSend = onSend,
        onNewline = onNewline,
        pasteImage = { onPasteImage != null && event.pasteClipboardImage(onPasteImage) },
    )
}

internal fun handleComposerKey(
    isImeComposing: Boolean,
    claimedByAutocomplete: () -> Boolean,
    action: ComposerKeyAction,
    onSend: () -> Unit,
    onNewline: (() -> Unit)?,
    pasteImage: () -> Boolean,
): Boolean {
    // Ahead of the controller, which commits on Enter: an IME's Enter confirms its own candidate.
    if (isImeComposing) return false
    // Preview events run root-to-leaf: decide Enter before this and an open suggestion list loses it.
    if (claimedByAutocomplete()) return true
    when (action) {
        ComposerKeyAction.Send -> {
            onSend()
            return true
        }

        ComposerKeyAction.Newline -> onNewline?.let {
            it()
            return true
        }

        ComposerKeyAction.Ignore -> Unit
    }
    // Deliberately not gated like the Enter chord: an iPad's hardware keyboard pastes but never sends.
    return pasteImage()
}

private fun KeyEvent.pasteClipboardImage(onPasteImage: (ByteArray) -> Unit): Boolean {
    if (type != KeyEventType.KeyDown || key != Key.V || !(isCtrlPressed || isMetaPressed)) return false
    val imageBytes = getImageFromClipboard() ?: return false
    onPasteImage(imageBytes)
    return true
}

internal enum class ComposerKeyAction {
    Send,
    Newline,
    Ignore,
}

internal class ComposerEnterChord(
    val isEnter: Boolean,
    val isKeyDown: Boolean,
    val isShiftPressed: Boolean,
    val isSendModifierPressed: Boolean,
)

internal fun KeyEvent.toEnterChord(): ComposerEnterChord = ComposerEnterChord(
    // macOS reports Return as NumPadEnter, so Key.Enter alone misses it there.
    isEnter = key == Key.Enter || key == Key.NumPadEnter,
    isKeyDown = type == KeyEventType.KeyDown,
    isShiftPressed = isShiftPressed,
    isSendModifierPressed = isCtrlPressed || isMetaPressed,
)

internal fun composerKeyAction(event: KeyEvent, enterSendsMessage: Boolean): ComposerKeyAction =
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
    if (chord.isSendModifierPressed) return ComposerKeyAction.Send

    return if (chord.isShiftPressed != enterSendsMessage) ComposerKeyAction.Send
    else ComposerKeyAction.Newline
}
