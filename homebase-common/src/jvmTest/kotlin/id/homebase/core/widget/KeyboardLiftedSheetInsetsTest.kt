package id.homebase.core.widget

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyboardLiftedSheetInsetsTest {

    private val density = Density(3f)
    private val nav = insets(bottom = 102)
    private val safeDrawing = insets(bottom = 102)

    private fun insets(bottom: Int) = WindowInsets(0, 0, 0, bottom)

    private fun bottom(screenIme: Int, sheetIme: Int, base: WindowInsets = safeDrawing) =
        KeyboardLiftedSheetInsets(base, insets(screenIme), insets(sheetIme), nav).getBottom(density)

    @Test
    fun `keeps the home indicator inset while the keyboard is down`() {
        assertEquals(102, bottom(screenIme = 0, sheetIme = 0))
    }

    @Test
    fun `drops the home indicator inset when the sheet is already lifted onto the keyboard`() {
        assertEquals(0, bottom(screenIme = 1005, sheetIme = 0))
    }

    @Test
    fun `leaves the insets alone when the sheet itself sees the keyboard`() {
        assertEquals(1005, bottom(screenIme = 1005, sheetIme = 1005, base = insets(1005)))
    }

    @Test
    fun `never goes negative for insets that already exclude the home indicator`() {
        assertEquals(0, bottom(screenIme = 1005, sheetIme = 0, base = insets(0)))
    }
}
