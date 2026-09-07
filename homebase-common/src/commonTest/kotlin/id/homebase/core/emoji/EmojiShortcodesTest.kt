package id.homebase.core.emoji

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val EXPECTED_SHORTCODE_EMOJI = mapOf(
    "party" to "🎉",
    "tada" to "🎉",
    "fire" to "🔥",
    "joy" to "😂",
    "heart" to "❤️",
    "100" to "💯",
    "smile" to "😄",
    "+1" to "👍️",
    "thumbsup" to "👍️",
    "rocket" to "🚀",
    "grinning" to "😀",
    "wave" to "👋",
    "eyes" to "👀",
    "pray" to "🙏",
)

class EmojiShortcodesTest {

    private val expectedEmoji = EXPECTED_SHORTCODE_EMOJI

    private fun replaceAtEnd(text: String, shortcodes: Map<String, String> = expectedEmoji) =
        replaceEmojiShortcode(text, text.length, shortcodes)

    @Test
    fun replacesCompletedShortcodes() {
        expectedEmoji.forEach { (shortcode, emoji) ->
            val result = assertNotNull(replaceAtEnd("hi :$shortcode:"), shortcode)
            assertEquals("hi $emoji", result.text, shortcode)
        }
    }

    @Test
    fun shortcodeLookupIsCaseInsensitive() {
        assertEquals("🎉", assertNotNull(replaceAtEnd(":PaRtY:")).text)
    }

    @Test
    fun leavesNonShortcodeTextUntouched() {
        listOf(
            "12:30",
            "12:30:",
            "https://x.y",
            "https:",
            "note: this",
            ":notreal:",
            "::",
            ":",
            "",
            "meeting at 9:00:",
        ).forEach { assertNull(replaceAtEnd(it), it) }
    }

    @Test
    fun replacesOnlyTheClosedTokenInSurroundingText() {
        val result = assertNotNull(replaceAtEnd("12:30 note: this :party:"))
        assertEquals("12:30 note: this 🎉", result.text)
    }

    @Test
    fun onlyFiresWhenTheCursorSitsAfterTheClosingColon() {
        assertNull(replaceEmojiShortcode(":party: done", 12, expectedEmoji))
        assertEquals(
            "🎉 done",
            assertNotNull(replaceEmojiShortcode(":party: done", 7, expectedEmoji)).text,
        )
    }

    @Test
    fun cursorLandsAfterTheInsertedEmoji() {
        val result = assertNotNull(replaceAtEnd("hi :party:"))
        assertEquals("hi 🎉", result.text)
        assertEquals(result.text.length, result.cursor)
        assertEquals(3, result.start)

        val mid = assertNotNull(replaceEmojiShortcode("a :fire: b", 8, expectedEmoji))
        assertEquals("a 🔥 b", mid.text)
        assertEquals(4, mid.cursor)
        assertEquals("🔥", mid.text.substring(2, mid.cursor))
    }

    @Test
    fun neverLeavesALoneSurrogateAndRoundTripsThroughJson() {
        listOf(
            ":party:" to 7,
            "🎉:fire:" to 8,
            "🎉 :heart:" to 10,
            // Spliced between two surrogate pairs, with more text after the cursor.
            "🎉:party:🎉" to 9,
            "👍️:+1:" to 7,
        ).forEach { (input, cursor) ->
            val result = assertNotNull(replaceEmojiShortcode(input, cursor, expectedEmoji), input)
            assertNoLoneSurrogate(result.text)
            assertEquals(result.text, Json.decodeFromString<String>(Json.encodeToString(result.text)))
        }
        assertEquals("🎉🎉🎉", assertNotNull(replaceEmojiShortcode("🎉:party:🎉", 9, expectedEmoji)).text)
    }

    @Test
    fun aTrailingColonAfterAnEmojiNeverSplitsTheSurrogatePair() {
        assertNull(replaceAtEnd("🎉:"))
    }
}

private fun assertNoLoneSurrogate(text: String) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isHighSurrogate() -> {
                assertTrue(
                    i + 1 < text.length && text[i + 1].isLowSurrogate(),
                    "lone high surrogate at $i in $text",
                )
                i += 2
            }

            c.isLowSurrogate() -> throw AssertionError("lone low surrogate at $i in $text")
            else -> i++
        }
    }
}
