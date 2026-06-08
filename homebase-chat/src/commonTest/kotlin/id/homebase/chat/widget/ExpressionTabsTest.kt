package id.homebase.chat.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpressionTabsTest {
    @Test fun default_is_emoji() {
        assertEquals(ExpressionTab.Emoji, ExpressionTab.Default)
    }

    @Test fun gifs_off_shows_emoji_then_stickers_only() {
        val tabs = expressionTabs(gifsEnabled = false)
        assertEquals(listOf(ExpressionTab.Emoji, ExpressionTab.Stickers), tabs)
        assertFalse(tabs.contains(ExpressionTab.Gifs))
    }

    @Test fun gifs_on_appends_gifs_last() {
        val tabs = expressionTabs(gifsEnabled = true)
        assertEquals(listOf(ExpressionTab.Emoji, ExpressionTab.Stickers, ExpressionTab.Gifs), tabs)
        assertTrue(tabs.last() == ExpressionTab.Gifs)
    }
}
