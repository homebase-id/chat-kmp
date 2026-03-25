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

fun RichTextState.applyMarkDownContent(
    content: String,
): RichTextState {
    return this.apply {
        try {
            setMarkdown(content)
        } catch (_: Exception) {
            try {
                setMarkdown(fixProblematicMarkdownText(content))
            } catch (e: Exception) {
                Logger.e(tag = "RichTextExtensions") { "Error setting markdown: $e" }
                setText(content)
            }
        }
    }
}

/*
This fixed Markdown that is invalid for RichTextState by removing empty lines if follow by non-empty lines with initial spaces
 */
private fun fixProblematicMarkdownText(input: String) : String {
    val lines = input.lines()
    val result = mutableListOf<String>()

    var i = 0
    while (i < lines.size) {
        val currentLine = lines[i]
        val isCurrentEmpty = currentLine.isBlank()

        // Check if current line is empty and there's a next line
        if (isCurrentEmpty && i < lines.size - 1) {
            val nextLine = lines[i + 1]
            val nextHasContent = nextLine.isNotBlank()
            val nextStartsWithSpaces = nextLine.isNotEmpty() && nextLine[0].isWhitespace() && nextLine[0] != '\n'

            // Skip this empty line if next line has content and starts with spaces
            if (nextHasContent && nextStartsWithSpaces) {
                i++
                continue
            }
        }

        result.add(currentLine)
        i++
    }

    return result.joinToString("\n")
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