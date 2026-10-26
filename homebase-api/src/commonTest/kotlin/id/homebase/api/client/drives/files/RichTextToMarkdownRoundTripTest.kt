package id.homebase.api.client.drives.files

import id.homebase.api.util.ChatMarkdownFlavour
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals

/** Parses the emitted markdown with the app's parser and checks every letter keeps its marks. */
class RichTextToMarkdownRoundTripTest {

    private data class M(val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false, val code: Boolean = false)

    private fun leaf(text: String, m: M) =
        RichTextNode(text = text, bold = m.bold, italic = m.italic, strikethrough = m.strike, code = m.code)

    private val backslashEscape = Regex("\\\\([!-/:-@\\[-`{-~])")

    private fun parsed(markdown: String): List<Pair<Char, M>> {
        val out = mutableListOf<Pair<Char, M>>()
        fun walk(node: ASTNode, m: M) {
            val text = markdown.substring(node.startOffset, node.endOffset)
            when (node.type) {
                MarkdownElementTypes.STRONG -> node.children.drop(2).dropLast(2).forEach { walk(it, m.copy(bold = true)) }
                MarkdownElementTypes.EMPH -> node.children.drop(1).dropLast(1).forEach { walk(it, m.copy(italic = true)) }
                GFMElementTypes.STRIKETHROUGH -> node.children.drop(2).dropLast(2).forEach { walk(it, m.copy(strike = true)) }
                MarkdownElementTypes.CODE_SPAN -> {
                    val inner = text.trim('`')
                    val content = if (inner.length > 2 && inner.startsWith(' ') && inner.endsWith(' ')) inner.substring(1, inner.length - 1) else inner
                    content.forEach { out.add(it to m.copy(code = true)) }
                }
                MarkdownTokenTypes.EMPH, GFMTokenTypes.TILDE -> text.forEach { out.add(it to m) }
                else -> if (node.children.isEmpty()) {
                    text.replace(backslashEscape, "$1").forEach { out.add(it to m) }
                } else {
                    node.children.forEach { walk(it, m) }
                }
            }
        }
        walk(MarkdownParser(ChatMarkdownFlavour).buildMarkdownTreeFromString(markdown), M())
        return out
    }

    private fun assertRoundTrips(vararg leaves: Pair<String, M>) = assertRoundTrips(leaves.toList(), strictMarks = true)

    private fun assertRoundTrips(leaves: List<Pair<String, M>>, strictMarks: Boolean) {
        val markdown = richTextToMarkdown(listOf(RichTextNode(type = "p", children = leaves.map { leaf(it.first, it.second) })))
        val expected = leaves.flatMap { (text, m) -> text.map { it to m } }
        val actual = parsed(markdown)
        val context = "markdown was: $markdown"
        assertEquals(expected.joinToString("") { it.first.toString() }.trim(), actual.joinToString("") { it.first.toString() }, context)
        if (!strictMarks) return
        val expectedLetters = expected.filter { it.first.isLetter() }
        val actualLetters = actual.filter { it.first.isLetter() }
        assertEquals(expectedLetters.map { it.first to markOf(it.second) }, actualLetters.map { it.first to markOf(it.second) }, context)
    }

    // A code span cannot carry other marks where its backtick touches a word, so code only checks code.
    private fun markOf(m: M) = if (m.code) M(code = true) else m

    private val plain = M()
    private val b = M(bold = true)
    private val i = M(italic = true)
    private val bi = M(bold = true, italic = true)

    @Test
    fun bold_ending_in_punctuation_before_a_word() = assertRoundTrips("hi," to b, "x" to plain)

    @Test
    fun bold_starting_with_punctuation_after_a_word() = assertRoundTrips("a" to plain, "(b)" to b)

    @Test
    fun italic_then_bold_italic_reuses_the_open_italic() {
        assertEquals("*a**b***", richTextToMarkdown(listOf(RichTextNode(type = "p", children = listOf(leaf("a", i), leaf("b", bi))))))
        assertRoundTrips("a" to i, "b" to bi)
    }

    @Test
    fun strike_and_bold_inside_a_word_keep_the_text() {
        val markdown = richTextToMarkdown(
            listOf(RichTextNode(type = "p", children = listOf(leaf("x", plain), leaf("a", M(bold = true, strike = true)), leaf("y", plain))))
        )
        assertEquals("xay", parsed(markdown).joinToString("") { it.first.toString() }, "markdown was: $markdown")
    }

    @Test
    fun overlapping_marks_inside_a_word_keep_the_text() =
        assertRoundTrips(listOf("a" to b, "a" to bi, "a" to i), strictMarks = false)

    // Rule of 3 leaves no CommonMark form for some mark changes between two letters; those keep the text only.
    private fun changesInsideAWord(leaves: List<Pair<String, M>>) = leaves.zipWithNext().any { (x, y) ->
        x.first.last().isLetter() && y.first.first().isLetter() && x.second != y.second &&
            x.second.copy(code = false) != plain && y.second.copy(code = false) != plain
    }

    @Test
    fun every_mark_combination_next_to_punctuation_round_trips() {
        val texts = listOf("a", "(b)", "c,", " d")
        val marks = listOf(plain, b, i, bi, M(code = true), M(bold = true, code = true), M(strike = true))
        val leaves = texts.flatMap { t -> marks.map { t to it } }
        for (x in leaves) for (y in leaves) for (z in leaves) {
            val sequence = listOf(x, y, z)
            assertRoundTrips(sequence, strictMarks = !changesInsideAWord(sequence))
        }
    }
}
