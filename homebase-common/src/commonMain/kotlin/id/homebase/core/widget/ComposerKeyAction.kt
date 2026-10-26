package id.homebase.core.widget

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
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
    clipboardImage: () -> ByteArray? = ::getImageFromClipboard,
    arrowUpEditsLastMessage: Boolean = false,
    isComposerEmpty: () -> Boolean = { false },
    // False when there was nothing to edit, so the caret moves as usual.
    onEditLast: (() -> Boolean)? = null,
    onLink: (() -> Unit)? = null,
    onFormat: ((ComposerKeyAction) -> Boolean)? = null,
    onPasteOverSelection: ((clipboardText: () -> String?) -> Boolean)? = null,
    clipboardText: () -> String? = { null },
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
        onLink = onLink,
        onFormat = onFormat,
        pasteImage = {
            (onPasteImage != null && event.pasteClipboardImage(clipboardImage, onPasteImage)) ||
                (onPasteOverSelection != null && event.pasteTextOverSelection(clipboardText, onPasteOverSelection))
        },
    )
}

internal fun handleComposerKey(
    isImeComposing: Boolean,
    claimedByAutocomplete: () -> Boolean,
    action: ComposerKeyAction,
    onSend: () -> Unit,
    onNewline: (() -> Unit)?,
    onEditLast: (() -> Boolean)?,
    onLink: (() -> Unit)? = null,
    onFormat: ((ComposerKeyAction) -> Boolean)? = null,
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

        ComposerKeyAction.Link -> onLink?.let {
            it()
            return true
        }

        ComposerKeyAction.Bold,
        ComposerKeyAction.Italic,
        ComposerKeyAction.InlineCode,
        ComposerKeyAction.Strike,
        ComposerKeyAction.BulletList,
        ComposerKeyAction.NumberedList,
        ComposerKeyAction.Redo -> if (onFormat?.invoke(action) == true) return true

        // The open suggestion list already took Escape above; with none open it is not ours.
        ComposerKeyAction.DismissTrigger,
        ComposerKeyAction.Ignore -> Unit
    }
    // Deliberately not gated like the Enter chord: an iPad's hardware keyboard pastes but never sends.
    return pasteImage()
}

private fun KeyEvent.pasteClipboardImage(
    clipboardImage: () -> ByteArray?,
    onPasteImage: (ByteArray) -> Unit,
): Boolean {
    if (type != KeyEventType.KeyDown || key != Key.V || !(isCtrlPressed || isMetaPressed)) return false
    val imageBytes = clipboardImage() ?: return false
    onPasteImage(imageBytes)
    return true
}

private fun KeyEvent.pasteTextOverSelection(
    clipboardText: () -> String?,
    onPasteOverSelection: (() -> String?) -> Boolean,
): Boolean {
    if (type != KeyEventType.KeyDown || key != Key.V || !(isCtrlPressed || isMetaPressed)) return false
    return onPasteOverSelection(clipboardText)
}

enum class ComposerKeyAction {
    Send,
    Link,
    Newline,
    EditLast,
    Bold,
    Italic,
    InlineCode,
    Strike,
    BulletList,
    NumberedList,
    Redo,
    DismissTrigger,
    Ignore,
}

internal class ComposerKeyChord(
    val isEnter: Boolean,
    val isArrowUp: Boolean,
    val isKeyDown: Boolean,
    val isShiftPressed: Boolean,
    val isCtrlOrCmdPressed: Boolean,
    val isK: Boolean = false,
    val isB: Boolean = false,
    val isI: Boolean = false,
    val isE: Boolean = false,
    val isX: Boolean = false,
    val isY: Boolean = false,
    val isSeven: Boolean = false,
    val isEight: Boolean = false,
    val isEscape: Boolean = false,
    val isAltPressed: Boolean = false,
)

internal fun KeyEvent.toKeyChord(): ComposerKeyChord = ComposerKeyChord(
    // macOS reports Return as NumPadEnter, so Key.Enter alone misses it there.
    isEnter = key == Key.Enter || key == Key.NumPadEnter,
    isArrowUp = key == Key.DirectionUp,
    isKeyDown = type == KeyEventType.KeyDown,
    isShiftPressed = isShiftPressed,
    isCtrlOrCmdPressed = isCtrlPressed || isMetaPressed,
    isK = key == Key.K,
    isB = key == Key.B,
    isI = key == Key.I,
    isE = key == Key.E,
    isX = key == Key.X,
    isY = key == Key.Y,
    isSeven = key == Key.Seven,
    isEight = key == Key.Eight,
    isEscape = key == Key.Escape,
    // Windows reports AltGr as Ctrl+Alt, and AltGr+key types a character.
    isAltPressed = isAltPressed,
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
    if (!chord.isKeyDown) return ComposerKeyAction.Ignore
    // Not gated on handlesHardwareKeys: an iPad or Android hardware keyboard sends these chords too.
    formatAction(chord)?.let { return it }
    if (!handlesHardwareKeys) return ComposerKeyAction.Ignore
    if (chord.isArrowUp) {
        // Shift+Up, with or without Ctrl/Cmd, extends a selection.
        val editsLast = !chord.isShiftPressed &&
            (chord.isCtrlOrCmdPressed || (arrowUpEditsLastMessage && isComposerEmpty()))
        return if (editsLast) ComposerKeyAction.EditLast else ComposerKeyAction.Ignore
    }
    if (chord.isK) {
        return if (chord.isCtrlOrCmdPressed && !chord.isShiftPressed) ComposerKeyAction.Link else ComposerKeyAction.Ignore
    }
    if (!chord.isEnter) return ComposerKeyAction.Ignore
    if (chord.isCtrlOrCmdPressed) return ComposerKeyAction.Send

    return if (chord.isShiftPressed != enterSendsMessage) ComposerKeyAction.Send
    else ComposerKeyAction.Newline
}

private fun formatAction(chord: ComposerKeyChord): ComposerKeyAction? {
    if (chord.isEscape) return ComposerKeyAction.DismissTrigger
    if (!chord.isCtrlOrCmdPressed || chord.isAltPressed) return null
    return if (chord.isShiftPressed) when {
        chord.isX -> ComposerKeyAction.Strike
        chord.isEight -> ComposerKeyAction.BulletList
        chord.isSeven -> ComposerKeyAction.NumberedList
        else -> null
    } else when {
        chord.isB -> ComposerKeyAction.Bold
        chord.isI -> ComposerKeyAction.Italic
        chord.isE -> ComposerKeyAction.InlineCode
        chord.isY -> ComposerKeyAction.Redo
        else -> null
    }
}
