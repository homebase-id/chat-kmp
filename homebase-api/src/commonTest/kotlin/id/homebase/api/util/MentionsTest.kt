package id.homebase.api.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The acceptance half of these cases is transcribed from the web client's own receive-side test
 * (`dotyoucore-js` `ChatMessageItem.tsx`): a token is a mention iff it starts at the body start or
 * after whitespace, opens with `@`, and the rest of the token begins with `[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`.
 * If one of these ever flips, the two clients have stopped agreeing on what a mention is.
 */
class MentionsTest {

    private fun mentions(text: String): List<String> =
        findMentions(text).map { text.substring(it.range.first, it.range.last + 1) }

    private fun identities(text: String): List<String> = findMentions(text).map { it.identity }

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
        val ranges = findMentions(text).map { it.range }
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
        val ranges = findMentions(text).map { it.range }
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
        assertEquals(emptyList(), findMentions(""))
        assertEquals(emptyList(), findMentions("@"))
    }

    // --- Mention.identity: what the shape regex actually matched, as opposed to what gets painted.

    /**
     * The whole point of surfacing the identity separately: the decorated range reaches past it,
     * so `body.substring(range)` would never equal the odinId it names.
     */
    @Test
    fun identityStopsAtTheDomainWhileTheRangeCarriesOn() {
        val text = "hi @alice.example.test/inbox"
        assertEquals(listOf("@alice.example.test/inbox"), mentions(text))
        assertEquals(listOf("alice.example.test"), identities(text))
    }

    @Test
    fun identityDropsTheAtSignAndTrailingPunctuation() {
        assertEquals(listOf("alice.example.test"), identities("thanks @alice.example.test!"))
        assertEquals(listOf("alice.example.test"), identities("bye @alice.example.test."))
    }

    /** A last label too short for a TLD is not part of the identity, only of the decoration. */
    @Test
    fun identityStopsWhereTheShapeStops() {
        assertEquals(listOf("alice.example"), identities("hey @alice.example.t"))
    }

    @Test
    fun identitiesAreReportedForEveryMention() {
        assertEquals(
            listOf("alice.example.test", "bob.example.test"),
            identities("@alice.example.test and @bob.example.test both"),
        )
    }

    // --- mentionsIdentity: the shared "is this about me?" predicate (#1425 chip, #1417 notify).

    @Test
    fun mentionsIdentityFindsAPlainMention() {
        assertTrue(mentionsIdentity("hey @me.example.test how are you", "me.example.test"))
        assertTrue(mentionsIdentity("@me.example.test hi", "me.example.test"))
    }

    /** odinIds are domain names, and a sender can type one in any case. */
    @Test
    fun mentionsIdentityIsCaseInsensitive() {
        assertTrue(mentionsIdentity("hey @ME.Example.TEST", "me.example.test"))
        assertTrue(mentionsIdentity("hey @me.example.test", "ME.EXAMPLE.test"))
    }

    /** The case that makes slicing the range wrong — a path suffix must not hide the mention. */
    @Test
    fun mentionsIdentityMatchesThroughAPathSuffix() {
        assertTrue(mentionsIdentity("hi @me.example.test/inbox", "me.example.test"))
    }

    @Test
    fun mentionsIdentityMatchesThroughTrailingPunctuation() {
        assertTrue(mentionsIdentity("ping @me.example.test, please", "me.example.test"))
        assertTrue(mentionsIdentity("ping @me.example.test!", "me.example.test"))
    }

    /** A mention that is only a PREFIX of my odinId is somebody else. */
    @Test
    fun mentionsIdentityRejectsAPrefixOfMyOdinId() {
        assertFalse(mentionsIdentity("hey @example.test", "me.example.test"))
        assertFalse(mentionsIdentity("hey @me.example.tes", "me.example.test"))
    }

    /** ...and so is one my odinId is a prefix of — the impersonation direction. */
    @Test
    fun mentionsIdentityRejectsAnIdentityThatMerelyStartsWithMine() {
        assertFalse(mentionsIdentity("hey @me.example.test.evil.test", "me.example.test"))
        assertFalse(mentionsIdentity("hey @me.example.test-evil.test", "me.example.test"))
    }

    @Test
    fun mentionsIdentityPicksMeOutOfSeveralMentions() {
        val body = "@alice.example.test @me.example.test @bob.example.test"
        assertTrue(mentionsIdentity(body, "me.example.test"))
        assertFalse(mentionsIdentity(body, "carol.example.test"))
    }

    @Test
    fun mentionsIdentityIgnoresAnEmailAddress() {
        assertFalse(mentionsIdentity("write to me.example.test today", "me.example.test"))
        assertFalse(mentionsIdentity("write to you@me.example.test today", "me.example.test"))
    }

    @Test
    fun mentionsIdentityRejectsABlankOdinId() {
        assertFalse(mentionsIdentity("hey @me.example.test", ""))
        assertFalse(mentionsIdentity("hey @me.example.test", "   "))
    }

    @Test
    fun mentionsIdentityHandlesAnEmptyBody() {
        assertFalse(mentionsIdentity("", "me.example.test"))
    }

    /**
     * Markdown-blind, exactly like the scan it is built on: a mention inside a fenced code block
     * still names you. Refusing to DECORATE those is the renderer's job (its annotator never sees
     * code nodes), and #1417 wants the notification either way. An inline code span happens to be
     * excluded anyway — a backtick is not whitespace, so the `@` never opens a mention.
     */
    @Test
    fun mentionsIdentityIsMarkdownBlind() {
        assertTrue(mentionsIdentity("```\n@me.example.test\n```", "me.example.test"))
        assertFalse(mentionsIdentity("run `@me.example.test` verbatim", "me.example.test"))
    }
}
