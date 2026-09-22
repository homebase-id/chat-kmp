package id.homebase.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The emoji font must apply to emoji and nothing else — setting it globally wrecked ordinary
 * text, which is what these pin against.
 */
class EmojiRunsTest {

    private fun runsOf(s: String) = emojiRuns(s).map { s.substring(it.first, it.last + 1) }

    @Test
    fun plainText_hasNoRuns() {
        for (s in listOf(
            "Hello, world!",
            "See section 3. Then item #4 and 5 * 6 = 30",
            "Costs $5 — cheap (really), 50% off; a→b",
            "café naïve Zürich ß",
            "",
        )) {
            assertEquals(emptyList(), emojiRuns(s), "must not restyle plain text: \"$s\"")
        }
    }

    @Test
    fun simpleEmoji_isMatched() {
        assertEquals(listOf("🎉"), runsOf("party 🎉 time"))
        assertEquals(listOf("☀"), runsOf("sun ☀ out"))
    }

    @Test
    fun zwjSequence_staysOneRun() {
        // Splitting this would break the ligature and draw three separate people.
        assertEquals(listOf("👨‍👩‍👧"), runsOf("family 👨‍👩‍👧 here"))
    }

    @Test
    fun skinToneModifier_staysWithItsBase() {
        assertEquals(listOf("👍🏽"), runsOf("nice 👍🏽"))
    }

    @Test
    fun flag_staysOneRun() {
        assertEquals(listOf("🇬🇧"), runsOf("from 🇬🇧 today"))
    }

    @Test
    fun adjacentEmoji_mergeIntoOneRun() {
        assertEquals(listOf("🎉🎊"), runsOf("yay 🎉🎊 !"))
    }

    @Test
    fun keycap_needsTheVariationSelector() {
        // "3" alone is a digit and must stay text; the keycap sequence is emoji.
        assertEquals(emptyList(), emojiRuns("item 3 of 9"))
        assertTrue(runsOf("press 3️⃣ now").isNotEmpty(), "keycap sequence must match")
    }

    @Test
    fun textArrow_stays_text_unless_promoted() {
        assertEquals(emptyList(), emojiRuns("a → b"))
        assertTrue(runsOf("a →️ b").isNotEmpty(), "VS16 promotes it to emoji")
    }

    @Test
    fun runsAreOrderedAndNonOverlapping() {
        val s = "a 🎉 b 👍🏽 c 🇬🇧 d"
        val runs = emojiRuns(s)
        assertEquals(3, runs.size)
        var prevEnd = -1
        for (r in runs) {
            assertTrue(r.first > prevEnd, "runs must be ordered and disjoint: $runs")
            prevEnd = r.last
        }
    }

    @Test
    fun emojiAtStringBoundaries() {
        assertEquals(listOf("🎉"), runsOf("🎉 leading"))
        assertEquals(listOf("🎉"), runsOf("trailing 🎉"))
        assertEquals(listOf("🎉"), runsOf("🎉"))
    }
}
