package id.homebase.api.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance half of these cases is transcribed from the web client's own receive-side test
 * (`dotyoucore-js` `ChatMessageItem.tsx`): a token is a mention iff it starts at the body start or
 * after whitespace, opens with `@`, and the rest of the token begins with `[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`.
 * If one of these ever flips, the two clients have stopped agreeing on what a mention is.
 */
class MentionsTest {

    private fun mentions(text: String): List<String> =
        findMentionRanges(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun findsAPlainMention() {
        assertEquals(listOf("@alice.example.test"), mentions("hey @alice.example.test how are you"))
    }

    @Test
    fun findsAMentionAtTheStartOfTheBody() {
        assertEquals(listOf("@alice.example.test"), mentions("@alice.example.test hi"))
    }

    @Test
    fun findsAMentionAfterANewline() {
        assertEquals(listOf("@alice.example.test"), mentions("line one\n@alice.example.test"))
        assertEquals(listOf("@alice.example.test"), mentions("line one\r\n@alice.example.test"))
    }

    @Test
    fun findsSeveralMentions() {
        assertEquals(
            listOf("@alice.example.test", "@bob.example.test"),
            mentions("@alice.example.test and @bob.example.test both"),
        )
    }

    @Test
    fun ignoresAnEmailAddress() {
        assertEquals(emptyList(), mentions("write to alice@example.test today"))
        assertEquals(emptyList(), mentions("alice@example.test"))
    }

    @Test
    fun ignoresAHandleWithNoDomain() {
        assertEquals(emptyList(), mentions("hey @alice how are you"))
        assertEquals(emptyList(), mentions("@bob"))
    }

    /**
     * Web's regex only has to match a PREFIX of the token, so a too-short last label does not
     * disqualify a mention — `alice.example` already satisfies it. Both clients call this a
     * mention; asserting it here stops a future "tighten the regex" from silently diverging.
     */
    @Test
    fun acceptsATokenWhoseLastLabelIsTooShortForATld() {
        assertEquals(listOf("@alice.example.t"), mentions("hey @alice.example.t"))
    }

    @Test
    fun ignoresALeadingDot() {
        assertEquals(emptyList(), mentions("hey @.test"))
    }

    @Test
    fun ignoresABareAtSign() {
        assertEquals(emptyList(), mentions("meet @ 5"))
        assertEquals(emptyList(), mentions("@"))
    }

    @Test
    fun ignoresASecondAtSignInsideTheSameToken() {
        assertEquals(emptyList(), mentions("hey @foo@bar.example.test"))
    }

    @Test
    fun ignoresAnAtSignInsideAUrl() {
        assertEquals(emptyList(), mentions("see https://example.test/u/@alice.example.test now"))
    }

    /**
     * Web paints its link over the whole token, trailing punctuation and all; we trim back to the
     * last alphanumeric. Both agree this IS a mention — only the decoration's reach differs.
     */
    @Test
    fun trimsTrailingPunctuation() {
        assertEquals(listOf("@alice.example.test"), mentions("thanks @alice.example.test!"))
        assertEquals(listOf("@alice.example.test"), mentions("thanks @alice.example.test, bye"))
        assertEquals(listOf("@alice.example.test"), mentions("bye @alice.example.test."))
        assertEquals(listOf("@alice.example.test"), mentions("hi @alice.example.test)))"))
    }

    /**
     * An opening bracket is not whitespace, so `(@alice.example.test)` is not a mention — on web
     * either, whose regex needs `^` or `\s` before the `@`.
     */
    @Test
    fun ignoresAMentionOpenedByABracket() {
        assertEquals(emptyList(), mentions("(@alice.example.test) said"))
    }

    /**
     * The range is what to DECORATE, and it is not the identity: a token that keeps going with
     * alphanumerics carries on into the range. Anything asking "is the current user mentioned here?"
     * (#1417, self-mention highlighting) must therefore match the identity, not slice the range.
     */
    @Test
    fun rangeCanReachPastTheIdentity() {
        assertEquals(listOf("@alice.example.test/inbox"), mentions("hi @alice.example.test/inbox"))
    }

    @Test
    fun keepsASubdomainRun() {
        assertEquals(listOf("@a.b.c.example.test"), mentions("hi @a.b.c.example.test"))
    }

    @Test
    fun rangesAreAscendingAndNonOverlapping() {
        val text = "@a.example.test @b.example.test @c.example.test"
        val ranges = findMentionRanges(text)
        assertEquals(3, ranges.size)
        for (i in 1 until ranges.size) {
            assertTrue(ranges[i - 1].last < ranges[i].first)
        }
    }

    /**
     * Bodies carry emoji, and every range boundary must land between whole code points. The `@`
     * side is ASCII by definition; the closing side is the last letter of the domain, so an emoji
     * pressed right up against a mention can only ever fall outside the range.
     */
    @Test
    fun neverSplitsASurrogatePair() {
        val text = "😀 @alice.example.test😀 done"
        val ranges = findMentionRanges(text)
        assertEquals(listOf("@alice.example.test"), ranges.map { text.substring(it.first, it.last + 1) })
        for (range in ranges) {
            assertTrue(!text[range.first].isLowSurrogate())
            assertTrue(!text[range.last].isHighSurrogate())
        }
    }

    /** An emoji directly before the `@` is not whitespace, so the token is not a mention. */
    @Test
    fun requiresWhitespaceNotJustANonLetterBeforeTheAt() {
        assertEquals(emptyList(), mentions("😀@alice.example.test"))
    }

    @Test
    fun handlesAnEmptyBody() {
        assertEquals(emptyList(), findMentionRanges(""))
        assertEquals(emptyList(), findMentionRanges("@"))
    }
}
