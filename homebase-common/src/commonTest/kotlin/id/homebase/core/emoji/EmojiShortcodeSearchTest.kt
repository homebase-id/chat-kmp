package id.homebase.core.emoji

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun emoji(
    label: String,
    char: String = "X",
    tags: List<String>? = null,
    group: Int? = 0,
    order: Int? = 1,
) = Emoji(emoji = char, label = label, hexcode = label, group = group, order = order, tags = tags)

class EmojiShortcodeSearchTest {

    /**
     * Guards ranking quality against the real dataset. These are conventional shortcodes
     * people actually type; emojibase labels/tags reach most of them but not all — see
     * `deliberatelyUnreachable` for the ones only a real shortcode table can resolve.
     */
    @Test
    fun `common shortcodes surface the expected emoji from the bundled dataset`() = runTest {
        val all = EmojiParser.loadEmojiData().emojis

        val expected = mapOf(
            "grin" to "\uD83D\uDE00",
            "tada" to "\uD83C\uDF89",
            "rocket" to "\uD83D\uDE80",
            "thinking" to "\uD83E\uDD14",
            "sob" to "\uD83D\uDE2D",
            "shrug" to "\uD83E\uDD37",
            "skull" to "\uD83D\uDC80",
            "poop" to "\uD83D\uDCA9",
            "thumbs_up" to "\uD83D\uDC4D",
            "heart_eyes" to "\uD83D\uDE0D",
        )

        val failures = expected.mapNotNull { (query, want) ->
            val top = rankEmojiForShortcode(query, all).firstOrNull()?.emoji
            val normalized = top?.let { EmojiNormalization.normalize(it) }
            if (normalized == EmojiNormalization.normalize(want)) null else ":$query got $top want $want"
        }

        assertTrue(failures.isEmpty(), "shortcode ranking regressed: $failures")
    }

    @Test
    fun `queries shorter than the minimum return nothing`() {
        val all = listOf(emoji("smile"))
        assertTrue(rankEmojiForShortcode("", all).isEmpty())
        assertTrue(rankEmojiForShortcode("s", all).isEmpty())
        assertTrue(rankEmojiForShortcode("sm", all).isNotEmpty())
    }

    @Test
    fun `label prefix outranks word prefix outranks tag`() {
        val labelPrefix = emoji("smiling face", char = "A")
        val wordPrefix = emoji("cat with smirk", char = "B")
        val tagMatch = emoji("grinning", char = "C", tags = listOf("smug"))

        val ranked = rankEmojiForShortcode("sm", listOf(tagMatch, wordPrefix, labelPrefix))

        assertEquals(listOf("A", "B", "C"), ranked.map { it.emoji })
    }

    @Test
    fun `exact tag outranks tag prefix`() {
        val exact = emoji("thumbs up", char = "A", tags = listOf("ok"))
        val prefix = emoji("hand", char = "B", tags = listOf("okay"))

        val ranked = rankEmojiForShortcode("ok", listOf(prefix, exact))

        assertEquals(listOf("A", "B"), ranked.map { it.emoji })
    }

    @Test
    fun `emojibase order breaks ties within a tier`() {
        val later = emoji("smile big", char = "A", order = 90)
        val earlier = emoji("smile wide", char = "B", order = 2)

        val ranked = rankEmojiForShortcode("smile", listOf(later, earlier))

        assertEquals(listOf("B", "A"), ranked.map { it.emoji })
    }

    @Test
    fun `regional indicators and skin tone components are never suggested`() {
        val regionalIndicator = emoji("regional indicator S", char = "A", group = null)
        val skinTone = emoji("medium skin tone", char = "B", group = 2)
        val real = emoji("skier", char = "C", group = 1)

        val ranked = rankEmojiForShortcode("sk", listOf(regionalIndicator, skinTone, real))

        assertEquals(listOf("C"), ranked.map { it.emoji })
    }

    @Test
    fun `matching is case insensitive`() {
        val all = listOf(emoji("Smiling Face", char = "A", tags = listOf("Happy")))

        assertEquals(1, rankEmojiForShortcode("smi", all).size)
        assertEquals(1, rankEmojiForShortcode("SMI", all).size)
        assertEquals(1, rankEmojiForShortcode("happy", all).size)
    }

    @Test
    fun `results are capped at the limit`() {
        val all = (1..50).map { emoji("smile $it", char = "$it", order = it) }

        assertEquals(EmojiShortcodeMaxSuggestions, rankEmojiForShortcode("smile", all).size)
        assertEquals(3, rankEmojiForShortcode("smile", all, limit = 3).size)
    }

    @Test
    fun `underscored shortcodes reach space and hyphen separated labels`() {
        val thumbsUp = emoji("thumbs up", char = "A")
        val heartEyes = emoji("smiling face with heart-eyes", char = "B")
        val all = listOf(thumbsUp, heartEyes)

        assertEquals(listOf("A"), rankEmojiForShortcode("thumbs_up", all).map { it.emoji })
        assertEquals(listOf("A"), rankEmojiForShortcode("thumbsup", all).map { it.emoji })
        assertEquals(listOf("B"), rankEmojiForShortcode("heart_eyes", all).map { it.emoji })
        assertEquals(listOf("B"), rankEmojiForShortcode("hearteyes", all).map { it.emoji })
    }

    @Test
    fun `separator insensitive matches rank below every literal match`() {
        val literal = emoji("upset", char = "A")
        val separatorOnly = emoji("ups et", char = "B")

        assertEquals(listOf("A", "B"), rankEmojiForShortcode("upset", listOf(separatorOnly, literal)).map { it.emoji })
    }

    @Test
    fun `an all-separator query matches nothing`() {
        val all = listOf(emoji("thumbs up"))

        assertTrue(rankEmojiForShortcode("__", all).isEmpty())
        assertTrue(rankEmojiForShortcode(" - _", all).isEmpty())
    }

    @Test
    fun `non-matching query returns nothing`() {
        val all = listOf(emoji("smile", tags = listOf("happy")))

        assertTrue(rankEmojiForShortcode("zzz", all).isEmpty())
    }

    @Test
    fun `substring match is a last resort behind tag matches`() {
        val substring = emoji("unamused", char = "A")
        val tagPrefix = emoji("grin", char = "B", tags = listOf("amusing"))

        val ranked = rankEmojiForShortcode("amus", listOf(substring, tagPrefix))

        assertEquals(listOf("B", "A"), ranked.map { it.emoji })
    }
}
