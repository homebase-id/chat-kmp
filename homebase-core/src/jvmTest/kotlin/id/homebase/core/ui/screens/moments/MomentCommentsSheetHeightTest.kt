package id.homebase.core.ui.screens.moments

import kotlin.test.Test
import kotlin.test.assertEquals

class MomentCommentsSheetHeightTest {
    @Test
    fun keyboardDownKeepsTheBaseFraction() {
        assertEquals(0.6f, commentsSheetHeightFraction(0.6f, imeVisible = false))
    }

    @Test
    fun keyboardUpGrowsTheSheetSoTheThreadStaysVisible() {
        assertEquals(COMMENTS_SHEET_KEYBOARD_FRACTION, commentsSheetHeightFraction(0.6f, imeVisible = true))
        assertEquals(COMMENTS_SHEET_KEYBOARD_FRACTION, commentsSheetHeightFraction(0.65f, imeVisible = true))
    }

    @Test
    fun keyboardNeverShrinksATallerSheet() {
        assertEquals(1f, commentsSheetHeightFraction(1f, imeVisible = true))
    }
}
