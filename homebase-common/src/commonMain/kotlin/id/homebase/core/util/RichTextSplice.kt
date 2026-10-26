package id.homebase.core.util

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.util.longestBacktickRun

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
private const val LIST_BULLETS = "\u2022\u25E6\u25AA\u25AB\u2023\u25CF\u25CB\u25A0"

data class Splice(val at: Int, val delete: Int, val insert: String)

data class BlockEdit(val splices: List<Splice>, val selection: TextRange) {
    fun applyTo(text: String): String =
        splices.asReversed().fold(text) { acc, s -> acc.replaceRange(s.at, s.at + s.delete, s.insert) }
}

data class BlockLine(val start: Int, val end: Int, val isList: Boolean = false)

private fun String.plainLines(): List<BlockLine> {
    val lines = ArrayList<BlockLine>()
    var start = 0
    while (true) {
        val nl = indexOf('\n', start)
        if (nl < 0) {
            lines += BlockLine(start, length)
            return lines
        }
        lines += BlockLine(start, nl)
        start = nl + 1
    }
}

private fun String.isListMarkerAt(start: Int, end: Int): Boolean {
    if (start >= end) return false
    if (this[start] in LIST_BULLETS) return start + 1 < end && this[start + 1] == ' '
    var i = start
    while (i < end && this[i].isDigit()) i++
    return i > start && i + 1 < end && this[i] == '.' && this[i + 1] == ' '
}

fun RichTextState.blockLines(): List<BlockLine> {
    val text = annotatedString.text
    val ranges = annotatedString.paragraphStyles.map { it.start to it.end }.sortedBy { it.first }
    if (ranges.isEmpty()) return listOf(BlockLine(0, text.length))
    return ranges.mapIndexed { i, (start, end) ->
        val lineEnd = if (i < ranges.lastIndex) end - 1 else end
        BlockLine(start, lineEnd, text.isListMarkerAt(start, lineEnd))
    }
}

private fun List<BlockLine>.touching(sel: TextRange): List<BlockLine> {
    val last = if (sel.max > sel.min && any { it.start == sel.max }) sel.max - 1 else sel.max
    return filter { it.start <= last && it.end >= sel.min }
}

private fun List<Splice>.caretAfter(at: Int): Int = at + sumOf { it.insert.length - it.delete }

private fun linePrefixEdit(text: String, lines: List<BlockLine>, selection: TextRange, prefix: String): BlockEdit {
    val sel = text.codePointBoundedRange(selection)
    val targets = lines.touching(sel).filterNot { it.isList }
    if (targets.isEmpty()) return BlockEdit(emptyList(), sel)
    val has = { l: BlockLine -> l.end - l.start >= prefix.length && text.startsWith(prefix, l.start) }
    val allPrefixed = targets.all(has)
    val splices = targets.mapNotNull {
        when {
            allPrefixed -> Splice(it.start, prefix.length, "")
            has(it) -> null
            else -> Splice(it.start, 0, prefix)
        }
    }
    return BlockEdit(splices, TextRange(splices.caretAfter(targets.last().end)))
}

private fun codeBlockEdit(text: String, lines: List<BlockLine>, selection: TextRange): BlockEdit {
    val sel = text.codePointBoundedRange(selection)
    if (sel.collapsed) {
        val line = lines.touching(sel).firstOrNull()
        if (line?.isList == true) return BlockEdit(emptyList(), sel)
        val lead = if (line == null || sel.min == line.start) "" else "\n"
        val trail = if (line != null && sel.min < line.end) "\n" else ""
        val insert = "$lead$FENCE\n\n$FENCE$trail"
        return BlockEdit(listOf(Splice(sel.min, 0, insert)), TextRange(sel.min + lead.length + FENCE.length + 1))
    }
    val touched = lines.touching(sel)
    if (touched.any { it.isList }) return BlockEdit(emptyList(), sel)
    if (touched.size <= 1) {
        val body = text.substring(sel.min, sel.max).trimEnd('\n')
        if (body.isEmpty()) return codeBlockEdit(text, lines, TextRange(sel.min))
        val end = sel.min + body.length
        val ticks = "`".repeat(body.longestBacktickRun() + 1)
        val pad = if (body.first() == '`' || body.last() == '`') " " else ""
        val splices = listOf(Splice(sel.min, 0, ticks + pad), Splice(end, 0, pad + ticks))
        return BlockEdit(splices, TextRange(splices.caretAfter(end)))
    }
    val start = touched.first().start
    val end = touched.last().end
    val fence = "`".repeat(maxOf(FENCE.length, text.substring(start, end).longestBacktickRun() + 1))
    val splices = listOf(Splice(start, 0, "$fence\n"), Splice(end, 0, "\n$fence"))
    return BlockEdit(splices, TextRange(splices.caretAfter(end)))
}

fun String.linePrefixEdit(selection: TextRange, prefix: String): BlockEdit =
    linePrefixEdit(this, plainLines(), selection, prefix)

fun String.quoteEdit(selection: TextRange): BlockEdit = linePrefixEdit(selection, QUOTE_PREFIX)

fun String.taskListEdit(selection: TextRange): BlockEdit = linePrefixEdit(selection, TASK_PREFIX)

fun String.codeBlockEdit(selection: TextRange): BlockEdit = codeBlockEdit(this, plainLines(), selection)

fun RichTextState.quoteEdit(): BlockEdit =
    linePrefixEdit(annotatedString.text, blockLines(), selection, QUOTE_PREFIX)

fun RichTextState.taskListEdit(): BlockEdit =
    linePrefixEdit(annotatedString.text, blockLines(), selection, TASK_PREFIX)

fun RichTextState.codeBlockEdit(): BlockEdit = codeBlockEdit(annotatedString.text, blockLines(), selection)

// richeditor mis-places a multi-character insert next to a list paragraph and mangles one containing a newline, so each char goes in alone.
fun RichTextState.applyBlockEdit(edit: BlockEdit) {
    for (s in edit.splices.asReversed()) {
        if (s.delete > 0) {
            selection = TextRange(s.at)
            removeTextRange(TextRange(s.at, s.at + s.delete))
        }
        if (s.insert.isNotEmpty()) {
            s.insert.forEachIndexed { i, c ->
                selection = TextRange(s.at + i)
                addTextAtIndex(s.at + i, c.toString())
            }
            val inserted = TextRange(s.at, s.at + s.insert.length)
            clearSpanStyles(inserted)
            clearRichSpans(inserted)
        }
    }
    selection = edit.selection
}
