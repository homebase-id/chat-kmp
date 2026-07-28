package id.homebase.core.util

import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the note round-trip invariant (issue #927 Section B): loading a saved
 * markdown body into the editor and reading it back must return the exact same
 * bytes, so the vault-note dirty check (VaultNoteEditorScreen) never reports a
 * spurious change and a re-save never silently drops structure.
 *
 * richeditor's setMarkdown is lossy for some inputs (it drops leading spaces on
 * an indented block after a blank line, without throwing); applyMarkDownContent
 * falls back to the byte-faithful setText in that case. These cases are the
 * evidence — verified against richeditor 1.0.0-rc14.
 */
class RichTextRoundTripProbeTest {

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
    )

    @Test
    fun applyMarkDownContent_roundTripsExactBytes() {
        for ((name, input) in samples) {
            val out = RichTextState().applyMarkDownContent(input).toMarkdown()
            assertEquals(input, out, "round-trip must be byte-faithful for [$name]")
        }
    }
}
