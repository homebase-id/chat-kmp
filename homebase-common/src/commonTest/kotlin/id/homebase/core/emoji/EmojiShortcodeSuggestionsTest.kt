package id.homebase.core.emoji

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiShortcodeSuggestionsTest {

    private val sample = mapOf(
        "party" to "🎉",
        "party_popper" to "🎉",
        "parrot" to "🦜",
        "parking" to "🅿️",
        "unicorn" to "🦄",
    )

    @Test
    fun queriesShorterThanTheMinimumReturnNothing() {
        assertTrue(emojiSuggestions("", sample).isEmpty())
        assertTrue(emojiSuggestions("p", sample).isEmpty())
        assertTrue(emojiSuggestions("pa", sample).isNotEmpty())
    }

    @Test
    fun exactBeatsPrefixBeatsSubstring() {
        val ranked = emojiSuggestions(
            "par",
            mapOf("par" to "A", "parrot" to "B", "spare" to "C"),
        )

        assertEquals(listOf("A", "B", "C"), ranked.map { it.emoji })
    }

    @Test
    fun withinATierTheShorterShortcodeWinsThenAlphabetical() {
        val ranked = emojiSuggestions(
            "par",
            mapOf("parking" to "A", "parrot" to "B", "params" to "C"),
        )

        assertEquals(listOf("params", "parrot", "parking"), ranked.map { it.shortcode })
    }

    @Test
    fun eachGlyphAppearsOnceUnderItsBestShortcode() {
        val ranked = emojiSuggestions("par", sample)

        assertEquals(listOf("party", "parrot", "parking"), ranked.map { it.shortcode })
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals("🎉", emojiSuggestions("PaRt", sample).first().emoji)
    }

    @Test
    fun resultsAreCappedAtTheLimit() {
        val many = (1..50).associate { "smile$it" to "$it" }

        assertEquals(EmojiSuggestionLimit, emojiSuggestions("smile", many).size)
        assertEquals(3, emojiSuggestions("smile", many, limit = 3).size)
    }

    /**
     * A completed `:party:` is the inline replacement's job, not the list's. The trailing colon
     * arrives in the query before the token closes, and nothing may match it.
     */
    @Test
    fun aQueryHoldingNonShortcodeCharactersMatchesNothing() {
        assertTrue(emojiSuggestions("party:", sample).isEmpty())
        assertTrue(emojiSuggestions("par ", sample).isEmpty())
        assertTrue(emojiSuggestions("par/", sample).isEmpty())
    }

    @Test
    fun nonMatchingQueryReturnsNothing() {
        assertTrue(emojiSuggestions("zzz", sample).isEmpty())
    }

    @Test
    fun theShippedIndexResolvesTheShortcodesPeopleActuallyType() = runTest {
        val index = EmojiShortcodes.index()

        val expected = mapOf(
            "par" to "🎉",
            "tad" to "🎉",
            "roc" to "🪨",
            "joy" to "😂",
            "thumbs" to "👍️",
            "sob" to "😭",
            "white_check" to "✅️",
        )

        val failures = expected.mapNotNull { (query, want) ->
            val top = emojiSuggestions(query, index).firstOrNull()?.emoji
            if (top == want) null else ":$query got $top want $want"
        }

        assertTrue(failures.isEmpty(), "shortcode ranking regressed: $failures")
    }
}
