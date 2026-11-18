package id.homebase.core.ui.screens.card

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class CardSheetDismissTest {

    private fun sheet() = AnchoredDraggableState(SheetValue.Dismissed).apply {
        updateAnchors(DraggableAnchors { SheetValue.Open at 0f; SheetValue.Dismissed at 1000f })
    }

    @Test
    fun aDismissThatInterruptsTheOpeningAnimationStillEndsTheSheet() = runComposeUiTest {
        val sheet = sheet()
        var dismissing by mutableStateOf(false)
        var ended = false
        setContent {
            LaunchedEffect(Unit) {
                launch { sheet.animateTo(SheetValue.Open) }
                sheet.awaitDismissed(SheetValue.Dismissed) { dismissing }
                ended = true
            }
            LaunchedEffect(Unit) {
                withFrameMillisAfter(32)
                dismissing = true
                sheet.animateTo(SheetValue.Dismissed)
            }
        }
        waitForIdle()
        assertTrue(ended)
    }

    @Test
    fun aDismissBeforeTheSheetMovesStillEndsIt() = runComposeUiTest {
        val sheet = sheet()
        var dismissing by mutableStateOf(false)
        var ended = false
        setContent {
            LaunchedEffect(Unit) {
                sheet.awaitDismissed(SheetValue.Dismissed) { dismissing }
                ended = true
            }
            LaunchedEffect(Unit) {
                dismissing = true
                sheet.animateTo(SheetValue.Dismissed)
            }
        }
        waitForIdle()
        assertTrue(ended)
    }

    @Test
    fun anOpenSheetDoesNotEndUntilItIsBackDown() = runComposeUiTest {
        val sheet = sheet()
        var ended = false
        setContent {
            LaunchedEffect(Unit) {
                launch { sheet.animateTo(SheetValue.Open) }
                sheet.awaitDismissed(SheetValue.Dismissed) { false }
                ended = true
            }
        }
        waitForIdle()
        assertFalse(ended)
    }
}

private suspend fun withFrameMillisAfter(ms: Long) {
    val start = withFrameMillis { it }
    while (withFrameMillis { it } - start < ms) Unit
}
