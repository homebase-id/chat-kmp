package id.homebase.core.util

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * The single splice the inline `:shortcode:` replacement and the composer autocomplete commit both
 * go through. A range end that lands between the two halves of a surrogate pair would leave a lone
 * surrogate behind, so each end snaps outward to the enclosing code-point boundary first.
 */
fun RichTextState.replaceTextRangeSafely(range: TextRange, replacement: String) {
    replaceTextRange(annotatedString.text.codePointBoundedRange(range), replacement)
}

fun String.codePointBoundedRange(range: TextRange): TextRange {
    val start = range.min.coerceIn(0, length)
    val end = range.max.coerceIn(0, length)
    return TextRange(
        if (splitsSurrogatePairAt(start)) start - 1 else start,
        if (splitsSurrogatePairAt(end)) end + 1 else end,
    )
}

private fun String.splitsSurrogatePairAt(index: Int): Boolean =
    index in 1..lastIndex && this[index - 1].isHighSurrogate() && this[index].isLowSurrogate()

const val QUOTE_PREFIX = "> "
const val TASK_PREFIX = "- [ ] "
private const val FENCE = "```"

data class BlockEdit(val range: TextRange, val replacement: String, val selection: TextRange) {
    fun applyTo(text: String): String = text.replaceRange(range.min, range.max, replacement)
}

private fun String.lineStart(index: Int): Int = lastIndexOf('\n', index - 1) + 1

private fun String.lineEnd(index: Int): Int = indexOf('\n', index).let { if (it < 0) length else it }

fun String.linePrefixEdit(selection: TextRange, prefix: String): BlockEdit {
    val sel = codePointBoundedRange(selection)
    val start = lineStart(sel.min)
    val lastIndex = if (sel.max > sel.min && this[sel.max - 1] == '\n') sel.max - 1 else sel.max
    val end = lineEnd(lastIndex)
    val lines = substring(start, end).split('\n')
    val allPrefixed = lines.all { it.startsWith(prefix) }
    val replacement = lines.joinToString("\n") {
        if (allPrefixed) it.removePrefix(prefix) else if (it.startsWith(prefix)) it else prefix + it
    }
    return BlockEdit(TextRange(start, end), replacement, TextRange(start + replacement.length))
}

fun String.quoteEdit(selection: TextRange): BlockEdit = linePrefixEdit(selection, QUOTE_PREFIX)

fun String.taskListEdit(selection: TextRange): BlockEdit = linePrefixEdit(selection, TASK_PREFIX)

fun String.codeBlockEdit(selection: TextRange): BlockEdit {
    val sel = codePointBoundedRange(selection)
    if (sel.collapsed) {
        val lead = if (sel.min == lineStart(sel.min)) "" else "\n"
        val replacement = "$lead$FENCE\n\n$FENCE"
        return BlockEdit(sel, replacement, TextRange(sel.min + lead.length + FENCE.length + 1))
    }
    val selected = substring(sel.min, sel.max)
    if ('\n' !in selected.trimEnd('\n')) {
        val body = selected.trimEnd('\n')
        val replacement = "`$body`"
        return BlockEdit(TextRange(sel.min, sel.min + body.length), replacement, TextRange(sel.min + replacement.length))
    }
    val start = lineStart(sel.min)
    val lastIndex = if (this[sel.max - 1] == '\n') sel.max - 1 else sel.max
    val end = lineEnd(lastIndex)
    val replacement = "$FENCE\n${substring(start, end)}\n$FENCE"
    return BlockEdit(TextRange(start, end), replacement, TextRange(start + replacement.length))
}

fun RichTextState.applyBlockEdit(edit: BlockEdit) {
    replaceTextRangeSafely(edit.range, edit.replacement)
    selection = edit.selection
}
