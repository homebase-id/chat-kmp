package id.homebase.chat.poll

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PollDescriptorTest {
    private fun valid(options: List<String> = listOf("A", "B")) =
        PollDescriptor(question = "Lunch?", options = options)

    @Test fun valid_minimal_poll_is_valid() = assertTrue(valid().isValid())

    @Test fun blank_question_is_invalid() =
        assertFalse(valid().copy(question = "   ").isValid())

    @Test fun question_over_140_codepoints_is_invalid() =
        assertFalse(valid().copy(question = "x".repeat(141)).isValid())

    @Test fun fewer_than_two_options_is_invalid() =
        assertFalse(valid(listOf("only")).isValid())

    @Test fun more_than_ten_options_is_invalid() =
        assertFalse(valid((1..11).map { "opt$it" }).isValid())

    @Test fun ten_options_is_valid() =
        assertTrue(valid((1..10).map { "opt$it" }).isValid())

    @Test fun blank_option_is_invalid() =
        assertFalse(valid(listOf("A", "  ")).isValid())

    @Test fun option_over_80_codepoints_is_invalid() =
        assertFalse(valid(listOf("A", "y".repeat(81))).isValid())

    @Test fun summaryLine_is_the_question() =
        assertTrue(valid().copy(question = "Pizza tonight?").summaryLine() == "Pizza tonight?")

    @Test fun closed_flag_does_not_affect_validity() =
        assertTrue(valid().copy(closed = true).isValid())
}
