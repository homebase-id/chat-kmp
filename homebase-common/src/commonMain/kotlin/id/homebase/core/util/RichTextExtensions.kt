package id.homebase.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.core.ui.theme.LightColors

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

fun RichTextState.applyDefaultStyling(
    linkColor: Color = LightColors.Primary,
): RichTextState {
    return this.apply {
        config.listIndent = 20
        config.linkColor = linkColor
        config.linkTextDecoration = TextDecoration.Underline
    }
}

/**
 * Loads existing markdown INTO a richeditor [RichTextState] (the WYSIWYG editor).
 *
 * Read-only display no longer goes through here — chat bubbles, captions, the
 * conversation-list preview and the search row now render with the mikepenz
 * CommonMark renderer (`ChatMarkdown`), which does not need this try/catch +
 * [fixProblematicMarkdownText] safety net. The one remaining caller is the
 * editor-load path: seeding the composer / the Vault note editor with a
 * previously-saved markdown body, where richeditor's `setMarkdown` still has the
 * documented "space after a blank line" fragility. Keep this wrapper for that
 * path; it is not dead code.
 */
fun RichTextState.applyMarkDownContent(
    content: String,
): RichTextState {
    return this.apply {
        try {
            setMarkdown(content)
            // richeditor's setMarkdown silently drops some structure (e.g. leading
            // spaces on an indented block after a blank line) without throwing, and
            // toMarkdown() would then persist that lossy form on the next save and
            // trip the editor's dirty check. setText round-trips the exact bytes, so
            // prefer it whenever the rich parse isn't byte-faithful — the note reads
            // as raw markdown but nothing is silently mangled (issue #927 Section B).
            if (toMarkdown() != content) setText(content)
        } catch (e: Exception) {
            Logger.e(tag = "RichTextExtensions") { "setMarkdown failed, preserving raw text: $e" }
            setText(content)
        }
    }
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