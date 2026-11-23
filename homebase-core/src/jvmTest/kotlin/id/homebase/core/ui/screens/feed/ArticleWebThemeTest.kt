package id.homebase.core.ui.screens.feed

import id.homebase.core.settings.ThemeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArticleWebThemeTest {
    @Test
    fun explicitThemeWinsOverSystem() {
        assertEquals(true, resolveIsDark(ThemeState.Dark, isSystemDark = false))
        assertEquals(false, resolveIsDark(ThemeState.Light, isSystemDark = true))
    }

    @Test
    fun systemThemeFollowsSystem() {
        assertEquals(true, resolveIsDark(ThemeState.System, isSystemDark = true))
        assertEquals(false, resolveIsDark(ThemeState.System, isSystemDark = false))
    }

    @Test
    fun injectionScriptCarriesTheTheme() {
        assertTrue("'prefersDark', '1'" in buildFeedInjectionScript("s", "t", isDark = true))
        assertTrue("'prefersDark', '0'" in buildFeedInjectionScript("s", "t", isDark = false))
    }

    @Test
    fun articleSkipsTheMinimumOverlayTime() {
        assertEquals(0L, ARTICLE_MIN_DISPLAY_MS)
    }
}
