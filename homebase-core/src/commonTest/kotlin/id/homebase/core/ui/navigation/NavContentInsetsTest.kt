package id.homebase.core.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class NavContentInsetsTest {

    private val density = Density(1f)
    private val landscapePhone = WindowInsets(left = 59, top = 0, right = 59, bottom = 21)

    private fun WindowInsets.sides() = listOf(
        getLeft(density, LayoutDirection.Ltr), getTop(density), getRight(density, LayoutDirection.Ltr), getBottom(density),
    )

    @Test
    fun callLabLeavesTheCallScreenTheWholeWindowInLandscape() {
        assertEquals(listOf(0, 0, 0, 0), navContentInsets(landscapePhone, paintsUnderNavigationBar = false, edgeToEdge = true).sides())
    }

    @Test
    fun ordinaryScreensKeepTheSideAndBottomSafeArea() {
        assertEquals(listOf(59, 0, 59, 21), navContentInsets(landscapePhone, paintsUnderNavigationBar = false, edgeToEdge = false).sides())
        assertEquals(listOf(59, 0, 59, 0), navContentInsets(landscapePhone, paintsUnderNavigationBar = true, edgeToEdge = false).sides())
    }
}
