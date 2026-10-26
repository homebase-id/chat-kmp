package id.homebase.core.util

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals

class RichTextLiveBlockEditTest {

    private fun load(markdown: String, selection: TextRange? = null) =
        RichTextState().applyMarkDownContent(markdown).also {
            it.selection = selection ?: TextRange(0, it.annotatedString.text.length)
        }

    private fun RichTextState.press(edit: RichTextState.() -> BlockEdit): String {
        applyBlockEdit(edit())
        return toMessageMarkdown()
    }

    @Test
    fun quote_keepsBoldAndLink() {
        val state = load("a **b** [link](u)")
        assertEquals("> a **b** [link](u)", state.press { quoteEdit() })
    }

    @Test
    fun quote_onLineStartingWithBold_prefixStaysOutsideTheSpan() {
        val state = load("**b** c")
        assertEquals("> **b** c", state.press { quoteEdit() })
    }

    @Test
    fun quote_toggleOff_restoresOriginalMarkdown() {
        val state = load("a **b** [link](u)")
        state.press { quoteEdit() }
        assertEquals("a **b** [link](u)", state.press { quoteEdit() })
    }

    @Test
    fun quote_prefixesEveryParagraph() {
        val state = load("one **x**\ntwo")
        assertEquals("> one **x**\n> two", state.press { quoteEdit() })
    }

    @Test
    fun taskList_keepsInlineFormatting() {
        val state = load("a **b** [link](u)")
        assertEquals("- [ ] a **b** [link](u)", state.press { taskListEdit() })
    }

    @Test
    fun taskList_toggleOffAfterDraftReload() {
        val first = load("a **b**")
        val saved = first.press { taskListEdit() }
        assertEquals("- [ ] a **b**", saved)

        val reloaded = load(saved)
        assertEquals("a **b**", reloaded.press { taskListEdit() })
    }

    @Test
    fun taskList_plainTextToggleOffAfterReload() {
        val reloaded = load("- [ ] buy milk")
        assertEquals("buy milk", reloaded.press { taskListEdit() })
    }

    @Test
    fun quote_leavesListItemsAlone() {
        val state = load("- one\n- two")
        assertEquals("- one\n- two", state.press { quoteEdit() })
    }

    @Test
    fun quote_mixedSelectionSkipsListItems() {
        val state = load("- one\n\nplain **b**")
        assertEquals("- one\n> \n> plain **b**", state.press { quoteEdit() })
    }

    @Test
    fun taskList_onListItemsIsNoOp() {
        val state = load("1. one\n2. two")
        assertEquals("1. one\n2. two", state.press { taskListEdit() })
    }

    @Test
    fun codeFence_wrapsStyledParagraphsWithoutRewritingThem() {
        val state = load("a **b**\nc")
        assertEquals("```\na **b**\nc\n```", state.press { codeBlockEdit() })
    }

    @Test
    fun codeFence_caretAtLineStartWithTrailingText() {
        val state = load("abc", TextRange(0))
        val out = state.press { codeBlockEdit() }
        assertEquals("```\n\n```\nabc", out)
    }

    @Test
    fun codeFence_caretMidLineWithTrailingText() {
        val state = load("abcd", TextRange(2))
        val out = state.press { codeBlockEdit() }
        assertEquals("ab\n```\n\n```\ncd", out)
    }

    @Test
    fun codeFence_doesNotCopyListBulletGlyphs() {
        val state = load("- one\n- two")
        assertEquals("- one\n- two", state.press { codeBlockEdit() })
    }

    @Test
    fun inlineCode_wrapsSelectionInsideStyledLine() {
        val state = load("a **b** c", TextRange(0, 1))
        assertEquals("`a` **b** c", state.press { codeBlockEdit() })
    }
}
