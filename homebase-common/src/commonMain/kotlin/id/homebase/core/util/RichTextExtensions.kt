package id.homebase.core.util

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState

fun RichTextState.programmaticBackspace() {
    val selection = this.selection
    val text = annotatedString.toString()
    if (selection.start == 0) return

    // 1. If text is selected, delete the selection and stop
    if (!selection.collapsed) {
        //state.removeText(selection.start, selection.end)
        removeSelectedText()
        return
    }

    val end = selection.start
    var start = end

    // --- STEP 1: Move back over the first "unit" ---
    start = findPrecedingCharacterStart(text, start)

    // --- STEP 2: Check if what we just passed is a Modifier or Joiner ---
    // If it is, we need to keep moving 'start' backwards to include the base emoji
    while (start > 0) {
        val lastCodePoint = getCodePointAt(text, start)
        val prevCodePoint = getCodePointAt(text, findPrecedingCharacterStart(text, start))

        start = if (isEmojiModifier(lastCodePoint) || isZWJ(lastCodePoint) || isVariationSelector(lastCodePoint)) {
            findPrecedingCharacterStart(text, start)
        } else if (isZWJ(prevCodePoint)) {
            // If there's a joiner behind us, we MUST grab the character behind that too
            findPrecedingCharacterStart(text, start)
        } else {
            break
        }
    }

    removeTextRange(TextRange(start = start, end = end))
}

private fun findPrecedingCharacterStart(text: String, offset: Int): Int {
    if (offset <= 0) return 0
    var newOffset = offset - 1
    // Handle UTF-16 surrogate pairs
    if (newOffset > 0 && text[newOffset].isLowSurrogate() && text[newOffset - 1].isHighSurrogate()) {
        newOffset--
    }
    return newOffset
}

private fun getCodePointAt(text: String, index: Int): Int {
    if (index < 0 || index >= text.length) return -1
    return if (text[index].isHighSurrogate() && index + 1 < text.length) {
        // Standard formula for converting surrogate pairs to a single Int CodePoint
        val high = text[index].code
        val low = text[index + 1].code
        0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
    } else {
        text[index].code
    }
}

// Logic for Emoji Skin Tone Modifiers (Fitzpatrick scale)
private fun isEmojiModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF
private fun isZWJ(cp: Int): Boolean = cp == 0x200D
private fun isVariationSelector(cp: Int): Boolean = cp in 0xFE00..0xFE0F