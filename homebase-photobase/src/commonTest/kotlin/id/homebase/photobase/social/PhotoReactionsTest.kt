package id.homebase.photobase.social

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoReactionsTest {
    @Test
    fun aFamilyOrAFlagIsAReactionButTextIsnt() {
        assertTrue(isReactionEmoji("👨‍👩‍👧‍👦"))
        assertTrue(isReactionEmoji("🏳️‍🌈"))
        assertFalse(isReactionEmoji(" "))
        assertFalse(isReactionEmoji("this is not one emoji"))
    }
}
