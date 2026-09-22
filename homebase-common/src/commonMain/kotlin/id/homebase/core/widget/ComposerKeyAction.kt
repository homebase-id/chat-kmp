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
    arrowUpEditsLastMessage: Boolean = false,
    isComposerEmpty: () -> Boolean = { false },
    // False when there was nothing to edit, so the caret moves as usual.
    onEditLast: (() -> Boolean)? = null,
): Modifier = onPreviewKeyEvent { event ->
    handleComposerKey(
        isImeComposing = event.isImeComposing(),
        claimedByAutocomplete = { autocomplete?.handleKeyEvent(event) == true },
        action = composerKeyAction(
            chord = event.toKeyChord(),
            enterSendsMessage = enterSendsMessage,
            // Mobile leaves Enter to the IME, which inserts the newline itself.
            handlesHardwareKeys = isDesktopOrWeb(),
            arrowUpEditsLastMessage = arrowUpEditsLastMessage,
            isComposerEmpty = isComposerEmpty,
        ),
        onSend = onSend,
        onNewline = onNewline,
        onEditLast = onEditLast,
        pasteImage = { onPasteImage != null && event.pasteClipboardImage(onPasteImage) },
    )
}

internal fun handleComposerKey(
    isImeComposing: Boolean,
    claimedByAutocomplete: () -> Boolean,
    action: ComposerKeyAction,
    onSend: () -> Unit,
    onNewline: (() -> Unit)?,
    onEditLast: (() -> Boolean)?,
    pasteImage: () -> Boolean,
): Boolean {
    // Ahead of the controller, which commits on Enter: an IME's Enter confirms its own candidate.
    if (isImeComposing) return false
    // Preview events run root-to-leaf: decide Enter or Up before this and an open suggestion list loses it.
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

        ComposerKeyAction.EditLast -> if (onEditLast?.invoke() == true) return true

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
    EditLast,
    Ignore,
}

internal class ComposerKeyChord(
    val isEnter: Boolean,
    val isArrowUp: Boolean,
    val isKeyDown: Boolean,
    val isShiftPressed: Boolean,
    val isCtrlOrCmdPressed: Boolean,
)

internal fun KeyEvent.toKeyChord(): ComposerKeyChord = ComposerKeyChord(
    // macOS reports Return as NumPadEnter, so Key.Enter alone misses it there.
    isEnter = key == Key.Enter || key == Key.NumPadEnter,
    isArrowUp = key == Key.DirectionUp,
    isKeyDown = type == KeyEventType.KeyDown,
    isShiftPressed = isShiftPressed,
    isCtrlOrCmdPressed = isCtrlPressed || isMetaPressed,
)

/** An Enter the composer decides on: it sends or breaks the line, whatever else is showing. */
internal fun KeyEvent.isModifiedEnter(): Boolean = with(toKeyChord()) {
    isEnter && isKeyDown && (isShiftPressed || isCtrlOrCmdPressed)
}

internal fun composerKeyAction(
    chord: ComposerKeyChord,
    enterSendsMessage: Boolean,
    handlesHardwareKeys: Boolean,
    arrowUpEditsLastMessage: Boolean,
    isComposerEmpty: () -> Boolean,
): ComposerKeyAction {
    if (!chord.isKeyDown || !handlesHardwareKeys) return ComposerKeyAction.Ignore
    if (chord.isArrowUp) {
        // Shift+Up, with or without Ctrl/Cmd, extends a selection.
        val editsLast = !chord.isShiftPressed &&
            (chord.isCtrlOrCmdPressed || (arrowUpEditsLastMessage && isComposerEmpty()))
        return if (editsLast) ComposerKeyAction.EditLast else ComposerKeyAction.Ignore
    }
    if (!chord.isEnter) return ComposerKeyAction.Ignore
    if (chord.isCtrlOrCmdPressed) return ComposerKeyAction.Send

    return if (chord.isShiftPressed != enterSendsMessage) ComposerKeyAction.Send
    else ComposerKeyAction.Newline
}
