package id.homebase.core.util

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the editor round-trip invariant (issues #927 Section B, #1450): loading a saved markdown
 * body into the editor and reading it back must return the exact same bytes, so the vault-note
 * dirty check never reports a spurious change and a re-save never silently drops structure.
 *
 * richeditor 1.0.0-rc14's setMarkdown is lossy for every block construct it cannot render inline —
 * `"```\nsome code\n```"` comes back as `"some code\n"`, `"---"` as `""`, `"> quoted"` as
 * `"quoted"`. applyMarkDownContent falls back to editing the raw source in that case.
 */
class RichTextRoundTripProbeTest {

    private val fence = "```\nsome code\n```"

    private val samples = mapOf(
        "plain" to "Just a plain sentence.",
        "heading" to "# Title",
        "bullets" to "- one\n- two\n- three",
        "numbered" to "1. one\n2. two",
        "blank-lines" to "Para one.\n\nPara two.\n\nPara three.",
        // setMarkdown silently strips the leading spaces here — the fallback must catch it.
        "blank-then-indent" to "Intro.\n\n    indented block",
        "bold-italic" to "Some **bold** and *italic* text.",
        "mixed" to "# Note\n\nBody with **bold**.\n\n- a\n- b\n\nEnd.",
        "empty" to "",
        "fence-bare" to fence,
        "fence-lang" to "```kotlin\nval x = 1\n```",
        "fence-single-line" to "```\nx\n```",
        "fence-in-doc" to "Intro\n\n```\nsome code\n```\n\nOutro",
        "blockquote" to "> quoted",
        "rule" to "---",
        "table" to "| a | b |\n| --- | --- |\n| 1 | 2 |",
    )

    @Test
    fun applyMarkDownContent_roundTripsExactBytes() {
        for ((name, input) in samples) {
            val out = RichTextState().applyMarkDownContent(input).toMarkdown()
            assertEquals(input, out, "round-trip must be byte-faithful for [$name]")
        }
    }

    @Test
    fun applyMarkDownContent_isIdempotentAcrossRepeatedEdits() {
        for ((name, input) in samples) {
            var body = input
            repeat(3) {
                body = RichTextState().applyMarkDownContent(body).toMarkdown()
                assertEquals(input, body, "repeated edits must not compound for [$name]")
            }
        }
    }

    @Test
    fun applyMarkDownContent_reusesOneEditorStateWithoutCarryingStructureOver() {
        val state = RichTextState().applyDefaultStyling()
        for (input in listOf(fence, "Just a plain sentence.", fence, "# Title", fence)) {
            assertEquals(input, state.applyMarkDownContent(input).toMarkdown())
        }
    }

    @Test
    fun fencedCode_loadsAsEditableSourceWithNoCodeDecoration() {
        val state = RichTextState().applyMarkDownContent(fence)
        state.selection = TextRange(0, state.annotatedString.length)

        assertTrue(
            state.annotatedString.toString().contains("```"),
            "the fence markers must be editable text, not a rendered code box",
        )
        assertFalse(state.isCodeSpan, "raw markdown editing must not carry a code decoration")
    }

    @Test
    fun losslessMarkdownStillLoadsAsRichText() {
        val state = RichTextState().applyMarkDownContent("Some **bold** text.")
        state.selection = TextRange(5, 9)

        assertTrue(state.currentSpanStyle.fontWeight != null, "WYSIWYG must survive for lossless bodies")
    }
}
