package id.homebase.core.util

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class RichTextSpliceTest {

    @Test
    fun anAsciiBoundedRangeIsLeftAlone() {
        assertEquals(TextRange(3, 7), "hi :par".codePointBoundedRange(TextRange(3, 7)))
    }

    @Test
    fun aRangeSplittingASurrogatePairSnapsOutwardToSwallowIt() {
        // "🎉" is a surrogate pair at 0..1, so offset 1 sits between its halves.
        assertEquals(TextRange(0, 2), "🎉ab".codePointBoundedRange(TextRange(1, 2)))
        assertEquals(TextRange(0, 4), "ab🎉".codePointBoundedRange(TextRange(0, 3)))
    }

    @Test
    fun aReversedRangeIsNormalised() {
        assertEquals(TextRange(2, 5), "hello".codePointBoundedRange(TextRange(5, 2)))
    }

    @Test
    fun offsetsPastTheEndOfTheTextAreClamped() {
        assertEquals(TextRange(0, 5), "hello".codePointBoundedRange(TextRange(0, 99)))
    }

    @Test
    fun snappedRangesNeverLeaveALoneSurrogate() {
        val text = "🎉:par🎉"
        for (start in 0..text.length) {
            for (end in start..text.length) {
                val snapped = text.codePointBoundedRange(TextRange(start, end))
                val spliced = text.substring(0, snapped.start) + "😄" + text.substring(snapped.end)
                assertNoLoneSurrogate(spliced)
            }
        }
    }
}

private fun assertNoLoneSurrogate(text: String) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isHighSurrogate() -> {
                check(i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    "lone high surrogate at $i in $text"
                }
                i += 2
            }

            c.isLowSurrogate() -> throw AssertionError("lone low surrogate at $i in $text")
            else -> i++
        }
    }
}
