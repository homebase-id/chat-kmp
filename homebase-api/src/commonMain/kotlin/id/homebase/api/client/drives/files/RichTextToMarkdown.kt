package id.homebase.api.client.drives.files

import id.homebase.api.util.longestBacktickRun

private val headingLevels = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6)
private val blockTypes = setOf("p", "paragraph", "blockquote", "code_block", "ul", "ol", "li") + headingLevels.keys

// `<` opens HTML blocks and `&` entities, both of which would swallow or rewrite literal text.
private const val markdownSpecialChars = "\\*_`~[]|<&"
private val newlineWithSpaces = Regex("\\s*\n\\s*")
private val headingClosingSequence = Regex("(^|\\s)(#+)$")

private data class Marks(val bold: Boolean, val italic: Boolean, val strike: Boolean, val code: Boolean)

private sealed interface Inline {
    class Text(val text: String, val marks: Marks) : Inline
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

private val wrapperMarks = listOf<Pair<String, (Marks) -> Boolean>>(
    "~~" to { it.strike },
    "**" to { it.bold },
    // `_` cannot open or close inside a word, `*` can.
    "*" to { it.italic },
)

private fun renderInline(nodes: List<RichTextNode>): String {
    val items = mutableListOf<Inline>()
    collectInline(nodes, items)

    val out = StringBuilder()
    val open = mutableListOf<Int>()
    var pendingMarks: Marks? = null
    val pending = StringBuilder()

    fun closeFrom(depth: Int) {
        while (open.size > depth) out.append(wrapperMarks[open.removeAt(open.lastIndex)].first)
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
                val wanted = wrapperMarks.indices.filter { wrapperMarks[it].second(marks) }
                var keep = 0
                while (keep < open.size && keep < wanted.size && open[keep] == wanted[keep]) keep++
                closeFrom(keep)
                for (i in wanted.drop(keep)) {
                    out.append(wrapperMarks[i].first)
                    open.add(i)
                }
                out.append(if (marks.code) codeSpan(core) else core)
                if (trailing.isNotEmpty()) closeFrom(0)
                out.append(trailing)
            }
        }
        pending.clear()
        pendingMarks = null
    }

    for (item in items) {
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
            }
        }
    }
    flush()
    closeFrom(0)
    return out.toString()
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
