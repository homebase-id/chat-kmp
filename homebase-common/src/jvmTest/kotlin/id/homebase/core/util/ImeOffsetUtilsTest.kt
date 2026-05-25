package id.homebase.core.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImeOffsetUtilsTest {

    private val density = Density(3f)

    private fun insets(bottom: Int) = WindowInsets(0, 0, 0, bottom)

    @Test
    fun `pureImeBottomPx subtracts nav bar from ime`() {
        val ime = insets(bottom = 914)
        val nav = insets(bottom = 102)
        assertEquals(812, pureImeBottomPx(ime, nav, density))
    }

    @Test
    fun `pureImeBottomPx returns zero when ime is zero`() {
        val ime = insets(bottom = 0)
        val nav = insets(bottom = 102)
        assertEquals(0, pureImeBottomPx(ime, nav, density))
    }

    @Test
    fun `pureImeBottomPx returns zero when nav exceeds ime`() {
        val ime = insets(bottom = 50)
        val nav = insets(bottom = 102)
        assertEquals(0, pureImeBottomPx(ime, nav, density))
    }

    @Test
    fun `pureImeBottomPx returns full ime when nav is zero`() {
        val ime = insets(bottom = 914)
        val nav = insets(bottom = 0)
        assertEquals(914, pureImeBottomPx(ime, nav, density))
    }

    @Test
    fun `isImeVisible returns true when ime has bottom inset`() {
        assertTrue(isImeVisible(insets(bottom = 914), density))
    }

    @Test
    fun `isImeVisible returns false when ime is zero`() {
        assertFalse(isImeVisible(insets(bottom = 0), density))
    }

    @Test
    fun `ImeOffsetState exposes correct values`() {
        val state = ImeOffsetState(
            imeInsets = insets(bottom = 900),
            navBarInsets = insets(bottom = 100),
            density = density,
        )
        assertEquals(800, state.pureImeBottomPx)
        assertEquals(900, state.imeBottomPx)
        assertTrue(state.isImeVisible)
    }

    @Test
    fun `ImeOffsetState with no keyboard`() {
        val state = ImeOffsetState(
            imeInsets = insets(bottom = 0),
            navBarInsets = insets(bottom = 100),
            density = density,
        )
        assertEquals(0, state.pureImeBottomPx)
        assertFalse(state.isImeVisible)
    }
}
