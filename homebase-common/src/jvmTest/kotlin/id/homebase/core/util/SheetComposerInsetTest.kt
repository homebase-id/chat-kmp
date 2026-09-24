package id.homebase.core.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SheetComposerInsetTest {

    private val imePx = mutableIntStateOf(0)
    private val navPx = 48

    private fun bottomInsets(px: () -> Int) = object : WindowInsets {
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getTop(density: Density) = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getBottom(density: Density) = px()
    }

    // ModalBottomSheet wraps its content in imePadding() on every platform, so the sheet foot already sits on the keyboard.
    @Test
    fun composerSitsOnTheKeyboardInsideALiftedSheet() = runComposeUiTest {
        val ime = ImeOffsetState(
            imeInsets = bottomInsets { imePx.intValue },
            navBarInsets = bottomInsets { navPx },
            density = Density(1f),
        )
        var rowBottom = 0f
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    val panel = rememberKeyboardPanelState(ime, hasSoftKeyboard = true)
                    Box(Modifier.size(400.dp, 600.dp).padding(bottom = imePx.intValue.dp)) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().sheetComposerInset(panel)) {
                            Box(
                                Modifier.fillMaxWidth().height(100.dp)
                                    .onGloballyPositioned { rowBottom = it.boundsInRoot().bottom },
                            )
                            Box(Modifier.fillMaxWidth().keyboardPanelSlot(panel, keyboardHandledByHost = true))
                        }
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(600f - navPx, rowBottom, "keyboard down: composer above the nav bar")
        imePx.intValue = 300
        waitForIdle()
        assertEquals(600f - 300, rowBottom, "keyboard up: composer directly on the keyboard")
    }
}
