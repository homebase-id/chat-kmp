package id.homebase.core.util

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class RichTextBlockFormatTest {

    private fun String.quote(sel: TextRange) = quoteEdit(sel).applyTo(this)

    @Test
    fun quote_oneLine_emptySelectionUsesCaretLine() {
        assertEquals("> hello", "hello".quote(TextRange(2)))
    }

    @Test
    fun quote_threeLines_prefixesEach() {
        val text = "a\nb\nc"
        assertEquals("> a\n> b\n> c", text.quote(TextRange(0, text.length)))
    }

    @Test
    fun quote_toggleOff_whenEveryLineHasIt() {
        val text = "> a\n> b\n> c"
        assertEquals("a\nb\nc", text.quote(TextRange(0, text.length)))
    }

    @Test
    fun quote_partiallyQuoted_prefixesOnlyMissing() {
        val text = "> a\nb"
        assertEquals("> a\n> b", text.quote(TextRange(0, text.length)))
    }

    @Test
    fun quote_selectionEndingAtLineStart_excludesNextLine() {
        assertEquals("> a\nb", "a\nb".quote(TextRange(0, 2)))
    }

    @Test
    fun codeFence_multiLineWrapsOnOwnLines() {
        val text = "one\ntwo"
        assertEquals("```\none\ntwo\n```", codeBlockEdit_apply(text, TextRange(0, text.length)))
    }

    @Test
    fun codeFence_multiLinePartialSelectionExpandsToWholeLines() {
        assertEquals("```\none\ntwo\n```", codeBlockEdit_apply("one\ntwo", TextRange(1, 5)))
    }

    @Test
    fun code_singleLineUsesInlineBackticks() {
        assertEquals("a `bc` d", codeBlockEdit_apply("a bc d", TextRange(2, 4)))
    }

    @Test
    fun codeFence_emptyInsertsFenceWithCaretInside() {
        val edit = "".codeBlockEdit(TextRange(0))
        assertEquals("```\n\n```", edit.applyTo(""))
        assertEquals(TextRange(4), edit.selection)
    }

    @Test
    fun codeFence_emptyMidLineStartsOnNewLine() {
        val edit = "ab".codeBlockEdit(TextRange(2))
        assertEquals("ab\n```\n\n```", edit.applyTo("ab"))
        assertEquals(TextRange(7), edit.selection)
    }

    @Test
    fun codeFence_caretAtLineStartWithTrailingTextClosesFence() {
        val edit = "abc".codeBlockEdit(TextRange(0))
        assertEquals("```\n\n```\nabc", edit.applyTo("abc"))
        assertEquals(TextRange(4), edit.selection)
    }

    @Test
    fun codeFence_caretMidLineClosesFenceBeforeTrailingText() {
        val edit = "abcd".codeBlockEdit(TextRange(2))
        assertEquals("ab\n```\n\n```\ncd", edit.applyTo("abcd"))
        assertEquals(TextRange(7), edit.selection)
    }

    @Test
    fun taskList_togglesOnAndOff() {
        val on = "buy milk".taskListEdit(TextRange(0)).applyTo("buy milk")
        assertEquals("- [ ] buy milk", on)
        assertEquals("buy milk", on.taskListEdit(TextRange(3)).applyTo(on))
    }

    @Test
    fun emoji_atLineEdgesIsNotSplit() {
        val text = "😀a\nb😀"
        val out = text.quote(TextRange(1, text.length - 1))
        assertEquals("> 😀a\n> b😀", out)
        val inline = "x😀y"
        assertEquals("x`😀`y", codeBlockEdit_apply(inline, TextRange(2, 3)))
    }

    @Test
    fun inlineCode_selectionContainingBacktickUsesLongerDelimiter() {
        assertEquals("a ``b`c`` d", codeBlockEdit_apply("a b`c d", TextRange(2, 5)))
    }

    @Test
    fun inlineCode_selectionEdgeBacktickIsPadded() {
        assertEquals("a `` `b `` d", codeBlockEdit_apply("a `b d", TextRange(2, 4)))
    }

    @Test
    fun codeFence_selectionOfOnlyALineBreakInsertsAnEmptyFence() {
        val edit = "ab\ncd".codeBlockEdit(TextRange(2, 3))
        assertEquals("ab\n```\n\n```\ncd", edit.applyTo("ab\ncd"))
        assertEquals(TextRange(7), edit.selection)
    }

    @Test
    fun codeFence_wrappedLinesContainingAFenceUseALongerOne() {
        val text = "```\nx"
        assertEquals("````\n```\nx\n````", codeBlockEdit_apply(text, TextRange(0, text.length)))
    }

    private fun codeBlockEdit_apply(text: String, sel: TextRange) = text.codeBlockEdit(sel).applyTo(text)
}
