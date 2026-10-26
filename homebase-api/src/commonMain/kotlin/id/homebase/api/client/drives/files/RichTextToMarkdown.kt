package id.homebase.api.client.drives.files

import id.homebase.api.util.ChatMarkdownFlavour
import id.homebase.api.util.longestBacktickRun
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser

private val headingLevels = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6)
private val blockTypes = setOf("p", "paragraph", "blockquote", "code_block", "ul", "ol", "li") + headingLevels.keys

// `<` opens HTML blocks and `&` entities, both of which would swallow or rewrite literal text.
private const val markdownSpecialChars = "\\*_`~[]|<&"
private val newlineWithSpaces = Regex("\\s*\n\\s*")
private val headingClosingSequence = Regex("(^|\\s)(#+)$")

private data class Marks(val bold: Boolean, val italic: Boolean, val strike: Boolean, val code: Boolean)

private sealed interface Inline {
    class Text(val text: String, var marks: Marks) : Inline
    class Raw(val markdown: String) : Inline
}

fun richTextToMarkdown(richText: RichText): String = renderBlocks(richText).joinToString("\n\n")

private fun hasBlockChild(node: RichTextNode) = node.children.orEmpty().any { it.type in blockTypes }

private fun renderBlocks(nodes: List<RichTextNode>): List<String> {
    val blocks = mutableListOf<String>()
    val run = mutableListOf<RichTextNode>()

    fun flushRun() {
        if (run.isEmpty()) return
        val text = renderInline(run).trim()
        if (text.isNotEmpty()) blocks.add(text)
        run.clear()
    }

    for (node in nodes) {
        val type = node.type.orEmpty()
        when {
            type == "code_block" -> {
                flushRun()
                blocks.add(renderCodeBlock(node))
            }

            type == "ul" || type == "ol" -> {
                flushRun()
                val list = renderList(node, ordered = type == "ol")
                if (list.isNotEmpty()) blocks.add(list)
            }

            type == "blockquote" -> {
                flushRun()
                val inner = renderBlocks(node.children.orEmpty()).joinToString("\n\n")
                if (inner.isNotEmpty()) {
                    blocks.add(inner.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" })
                }
            }

            type in headingLevels -> {
                flushRun()
                val text = renderInline(node.children.orEmpty()).replace(newlineWithSpaces, " ").trim()
                    .replace(headingClosingSequence) { it.groupValues[1] + "\\" + it.groupValues[2] }
                if (text.isNotEmpty()) blocks.add("#".repeat(headingLevels.getValue(type)) + " " + text)
            }

            hasBlockChild(node) -> {
                flushRun()
                blocks.addAll(renderBlocks(node.children.orEmpty()))
            }

            type !in blockTypes -> run.add(node)

            else -> {
                flushRun()
                val text = renderInline(node.children.orEmpty()).trim()
                if (text.isNotEmpty()) blocks.add(text)
            }
        }
    }
    flushRun()
    return blocks
}

private fun renderCodeBlock(node: RichTextNode): String {
    val children = node.children.orEmpty()
    val code = if (children.any { it.type == "code_line" }) {
        children.joinToString("\n") { it.plainText("") }
    } else {
        node.plainText("")
    }
    val fence = "`".repeat(maxOf(3, code.longestBacktickRun() + 1))
    return "$fence\n$code\n$fence"
}

private fun renderList(node: RichTextNode, ordered: Boolean): String {
    class Item(val marker: String, val lines: MutableList<String>)

    val items = mutableListOf<Item>()
    for (child in node.children.orEmpty()) {
        if ((child.type == "ul" || child.type == "ol") && items.isNotEmpty()) {
            val nested = renderList(child, ordered = child.type == "ol")
            if (nested.isNotEmpty()) items.last().lines.addAll(nested.lines())
            continue
        }
        val body = renderBlocks(if (child.type == "li") child.children.orEmpty() else listOf(child))
            .joinToString("\n")
        if (body.isEmpty()) continue
        val marker = if (ordered) "${items.size + 1}. " else "- "
        items.add(Item(marker, body.lines().toMutableList()))
    }
    return items.joinToString("\n") { item ->
        val indent = " ".repeat(item.marker.length)
        item.lines.mapIndexed { i, line ->
            when {
                i == 0 -> item.marker + line
                line.isEmpty() -> line
                else -> indent + line
            }
        }.joinToString("\n")
    }
}

private fun collectInline(nodes: List<RichTextNode>, out: MutableList<Inline>) {
    for (node in nodes) {
        when (node.type) {
            "mention" -> node.value?.let { out.add(Inline.Raw("@$it")) }

            "a", "link" -> {
                val url = node.url
                if (url.isNullOrEmpty()) {
                    collectInline(node.children.orEmpty(), out)
                } else {
                    val label = renderInline(node.children.orEmpty()).ifBlank { escapeText(url, false) }
                    val dest = if (url.any { it == ' ' || it == '(' || it == ')' || it == '<' || it == '>' }) {
                        "<" + url.replace("<", "%3C").replace(">", "%3E") + ">"
                    } else {
                        url
                    }
                    out.add(Inline.Raw("[$label]($dest)"))
                }
            }

            else -> {
                if (!node.children.isNullOrEmpty()) {
                    collectInline(node.children, out)
                } else if (node.type == null || node.type == "text" || node.type == "code_line" || node.text != null) {
                    val text = node.text
                    if (!text.isNullOrEmpty()) {
                        out.add(
                            Inline.Text(
                                text,
                                Marks(
                                    bold = node.bold == true || node.strong == true,
                                    italic = node.italic == true,
                                    strike = node.strikethrough == true,
                                    code = node.code == true || node.kbd == true,
                                ),
                            )
                        )
                    }
                }
            }
        }
    }
}

private class Wrapper(val delimiter: String, val has: (Marks) -> Boolean, val without: (Marks) -> Marks)

private val wrapperMarks = listOf(
    Wrapper("~~", { it.strike }) { it.copy(strike = false) },
    Wrapper("**", { it.bold }) { it.copy(bold = false) },
    // `_` cannot open or close inside a word, `*` can.
    Wrapper("*", { it.italic }) { it.copy(italic = false) },
)

private class Delimiter(val start: Int, val end: Int, val mark: Int, val opens: Boolean, val unit: Int)

private class Segment(val start: Int, val firstUnit: Int, val lastUnit: Int, val marks: Int)

private class RenderedInline(val markdown: String, val delimiters: List<Delimiter>, val segments: List<Segment>)

private fun markBits(marks: Marks) = wrapperMarks.indices.sumOf { if (wrapperMarks[it].has(marks)) 1 shl it else 0 }

private val punctuationCategories = setOf(
    CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION, CharCategory.START_PUNCTUATION,
    CharCategory.END_PUNCTUATION, CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
    CharCategory.OTHER_PUNCTUATION, CharCategory.MATH_SYMBOL,
)

// org.jetbrains:markdown's flanking test: punctuation categories plus math symbols and `$^` + backtick.
private fun Char.isMarkdownPunctuation() = this in "$^`" || category in punctuationCategories

// CommonMark left-flanking with `this` after the run; right-flanking with `this` before it.
private fun Char.flanks(other: Char) =
    !isWhitespace() && (!isMarkdownPunctuation() || other.isWhitespace() || other.isMarkdownPunctuation())

/** Whether the delimiter's whole run is left- and right-flanking. */
private fun RenderedInline.flanking(d: Delimiter, isDelimiter: BooleanArray): Pair<Boolean, Boolean> {
    val c = markdown[d.start]
    var s = d.start
    while (s > 0 && isDelimiter[s - 1] && markdown[s - 1] == c) s--
    var e = d.end
    while (e < markdown.length && isDelimiter[e] && markdown[e] == c) e++
    val before = markdown.getOrNull(s - 1) ?: ' '
    val after = markdown.getOrNull(e) ?: ' '
    return after.flanks(before) to before.flanks(after)
}

private fun Inline.isMovable() = this is Inline.Text && (marks.code || text.single().isMarkdownPunctuation())

private val parsedWrappers = mapOf(
    GFMElementTypes.STRIKETHROUGH to 0,
    MarkdownElementTypes.STRONG to 1,
    MarkdownElementTypes.EMPH to 2,
)

/** The first segment the app's parser reads with other marks, or next to a delimiter it left as text. */
private fun RenderedInline.misparsedSegment(): Segment? {
    val marksAt = IntArray(markdown.length)
    val consumed = BooleanArray(markdown.length)
    fun walk(node: ASTNode, marks: Int) {
        val mark = parsedWrappers[node.type]
        val inner = if (mark == null) marks else marks or (1 shl mark)
        if (mark != null) {
            for (i in wrapperMarks[mark].delimiter.indices) {
                consumed[node.startOffset + i] = true
                consumed[node.endOffset - 1 - i] = true
            }
        }
        for (i in node.startOffset until node.endOffset) marksAt[i] = inner
        node.children.forEach { walk(it, inner) }
    }
    walk(MarkdownParser(ChatMarkdownFlavour).parseInline(MarkdownElementTypes.PARAGRAPH, markdown, 0, markdown.length), 0)
    val leaked = delimiters.firstOrNull { d -> (d.start until d.end).any { !consumed[it] } }
    fun afterLeak(s: Segment) = leaked != null && if (leaked.opens) s.firstUnit >= leaked.unit else s.firstUnit > leaked.unit
    return segments.firstOrNull { marksAt[it.start] != it.marks || afterLeak(it) }
        ?: leaked?.let { l -> segments.lastOrNull { it.firstUnit <= l.unit } }
}

/**
 * Emits markdown that parses back to the same marks. A delimiter CommonMark won't flank loses its mark
 * from the edge punctuation or code span (`a**(b)**` becomes `a(**b)**`), else from the whole run
 * (`x~~**a**~~y`). A run the parser still pairs differently, which rule of 3 forces for some mark
 * changes inside a word, drops its marks.
 */
private fun renderInline(nodes: List<RichTextNode>): String {
    val items = mutableListOf<Inline>()
    collectInline(nodes, items)
    val units = items.flatMap { item ->
        if (item is Inline.Text && !item.marks.code && markBits(item.marks) != 0) item.text.map { Inline.Text(it.toString(), item.marks) } else listOf(item)
    }
    while (true) {
        val rendered = renderUnits(units)
        if (rendered.delimiters.isEmpty()) return rendered.markdown
        val isDelimiter = BooleanArray(rendered.markdown.length)
        for (d in rendered.delimiters) for (i in d.start until d.end) isDelimiter[i] = true
        val bad = rendered.delimiters.firstOrNull { d ->
            val (left, right) = rendered.flanking(d, isDelimiter)
            // An opener that could also close is read as a closer first, e.g. `*` in `*(a)*(b)`.
            if (d.opens) !left || (right && units[d.unit].isMovable()) else !right
        }
        if (bad != null) {
            val edge = units[bad.unit] as Inline.Text
            if (edge.isMovable()) {
                edge.marks = wrapperMarks[bad.mark].without(edge.marks)
            } else {
                val step = if (bad.opens) 1 else -1
                var i = bad.unit
                while (true) {
                    val unit = units.getOrNull(i) as? Inline.Text ?: break
                    if (!wrapperMarks[bad.mark].has(unit.marks)) break
                    unit.marks = wrapperMarks[bad.mark].without(unit.marks)
                    i += step
                }
            }
            continue
        }
        val misparsed = rendered.misparsedSegment() ?: return rendered.markdown
        var dropped = false
        for (i in misparsed.firstUnit..misparsed.lastUnit) {
            val unit = units[i] as? Inline.Text ?: continue
            val plain = unit.marks.copy(bold = false, italic = false, strike = false)
            if (unit.marks != plain) dropped = true
            unit.marks = plain
        }
        if (!dropped) return rendered.markdown
    }
}

private fun renderUnits(units: List<Inline>): RenderedInline {
    val out = StringBuilder()
    val delimiters = mutableListOf<Delimiter>()
    val segments = mutableListOf<Segment>()
    val open = mutableListOf<Int>()
    var lastCoreUnit = -1
    var pendingMarks: Marks? = null
    val pending = StringBuilder()
    val pendingUnits = mutableListOf<Int>()
    // Per mark, the last unit of the unbroken run each unit is in: the mark that ends first nests innermost.
    val runEnd = wrapperMarks.map { wrapper ->
        val on = BooleanArray(units.size) { (units[it] as? Inline.Text)?.marks?.let(wrapper.has) == true }
        IntArray(units.size).also { end ->
            for (i in units.indices.reversed()) end[i] = if (i + 1 < units.size && on[i] && on[i + 1]) end[i + 1] else i
        }
    }

    fun emit(mark: Int, opens: Boolean, unit: Int) {
        val delimiter = wrapperMarks[mark].delimiter
        delimiters.add(Delimiter(out.length, out.length + delimiter.length, mark, opens, unit))
        out.append(delimiter)
    }

    fun closeFrom(depth: Int) {
        while (open.size > depth) emit(open.removeAt(open.lastIndex), opens = false, unit = lastCoreUnit)
    }

    fun flush() {
        val marks = pendingMarks ?: return
        if (pending.isNotEmpty()) {
            val atLineStart = out.substring(out.lastIndexOf('\n') + 1).isBlank()
            val body = if (marks.code) pending.toString() else escapeText(pending.toString(), atLineStart)
            val core = body.trim()
            if (core.isEmpty()) {
                closeFrom(0)
                out.append(body)
            } else {
                val leading = body.substring(0, body.indexOf(core))
                val trailing = body.substring(leading.length + core.length)
                if (leading.isNotEmpty()) closeFrom(0)
                out.append(leading)
                val wanted = wrapperMarks.indices.filter { wrapperMarks[it].has(marks) }
                // Escaping never touches whitespace, so the trimmed lengths index the raw units too.
                val firstUnit = pendingUnits[leading.length]
                val lastUnit = pendingUnits[pendingUnits.size - trailing.length - 1]
                fun end(mark: Int) = runEnd[mark][firstUnit]
                var keep = 0
                while (keep < open.size && open[keep] in wanted && (keep == 0 || end(open[keep]) <= end(open[keep - 1]))) keep++
                while (keep > 0 && wanted.any { it !in open.subList(0, keep) && end(it) > end(open[keep - 1]) }) keep--
                closeFrom(keep)
                for (i in wanted.filter { it !in open }.sortedByDescending(::end)) {
                    emit(i, opens = true, unit = firstUnit)
                    open.add(i)
                }
                segments.add(Segment(out.length, firstUnit, lastUnit, markBits(marks)))
                out.append(if (marks.code) codeSpan(core) else core)
                lastCoreUnit = lastUnit
                if (trailing.isNotEmpty()) closeFrom(0)
                out.append(trailing)
            }
        }
        pending.clear()
        pendingUnits.clear()
        pendingMarks = null
    }

    units.forEachIndexed { index, item ->
        when (item) {
            is Inline.Raw -> {
                flush()
                closeFrom(0)
                // A `!` right before `[label](url)` would turn the link into an image.
                if (out.endsWith('!') && item.markdown.startsWith('[')) out.insert(out.lastIndex, '\\')
                out.append(item.markdown)
            }

            is Inline.Text -> {
                if (item.marks != pendingMarks) flush()
                pendingMarks = item.marks
                pending.append(item.text)
                repeat(item.text.length) { pendingUnits.add(index) }
            }
        }
    }
    flush()
    closeFrom(0)
    return RenderedInline(out.toString(), delimiters, segments)
}

private fun codeSpan(raw: String): String {
    // A code span renders a newline as a space anyway, and a raw one lets the next line start a block.
    val text = raw.replace('\n', ' ')
    val fence = "`".repeat(text.longestBacktickRun() + 1)
    val pad = if (text.startsWith('`') || text.endsWith('`')) " " else ""
    return fence + pad + text + pad + fence
}

private fun escapeText(text: String, atLineStart: Boolean): String {
    val sb = StringBuilder(text.length + 8)
    var lineStart = atLineStart
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c in markdownSpecialChars -> {
                sb.append('\\').append(c)
                lineStart = false
            }

            // `-` and `=` also start setext underlines and thematic breaks, not only list items.
            (c == '#' || c == '>' || c == '-' || c == '+' || c == '=') && lineStart -> {
                sb.append('\\').append(c)
                lineStart = false
            }

            c.isDigit() && lineStart -> {
                var j = i
                while (j < text.length && text[j].isDigit()) j++
                sb.append(text, i, j)
                if (j < text.length && (text[j] == '.' || text[j] == ')') &&
                    (j + 1 == text.length || text[j + 1] == ' ')
                ) {
                    sb.append('\\').append(text[j])
                    j++
                }
                i = j
                lineStart = false
                continue
            }

            else -> {
                sb.append(c)
                lineStart = c == '\n' || (c.isWhitespace() && lineStart)
            }
        }
        i++
    }
    return sb.toString()
}
