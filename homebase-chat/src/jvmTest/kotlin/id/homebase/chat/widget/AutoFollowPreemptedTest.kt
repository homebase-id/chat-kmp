@file:OptIn(ExperimentalTestApi::class)

package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AutoFollowPreemptedTest {

    @Test
    fun followsAnIncomingMessageAtTheBottom() = incomingAfter(preempt = null)

    @Test
    fun stillFollowsAfterAJumpPreemptsTheFollowAnimation() =
        incomingAfter { scrollToItem(layoutInfo.totalItemsCount - 1) }

    @Test
    fun stillFollowsAfterAnotherAnimationPreemptsTheFollowAnimation() =
        incomingAfter { animateScrollToItem(layoutInfo.totalItemsCount - 1) }

    private fun incomingAfter(preempt: (suspend LazyListState.() -> Unit)?) = runComposeUiTest {
        val state = LazyListState(firstVisibleItemIndex = 49)
        var count by mutableIntStateOf(50)
        val bottomItem = mutableStateOf<Pair<Uuid?, Boolean>>(null to false)
        val hasNewer = mutableStateOf(false)
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            LazyColumn(Modifier.size(300.dp, 400.dp), state = state) {
                items(count) { Box(Modifier.fillMaxWidth().height(50.dp)) }
            }
            AutoFollowNewItems(state, Unit, bottomItem, hasNewer)
        }
        waitForIdle()

        if (preempt != null) {
            mainClock.autoAdvance = false
            count++
            repeat(10) { if (!state.isScrollInProgress) mainClock.advanceTimeByFrame() }
            assertTrue(state.isScrollInProgress, "the auto-follow animation never started")
            scope.launch { state.preempt() }
            mainClock.autoAdvance = true
            waitForIdle()
            assertEquals(count - 1, state.layoutInfo.visibleItemsInfo.last().index)
        }

        count++
        waitForIdle()
        assertEquals(count - 1, state.layoutInfo.visibleItemsInfo.last().index)
    }
}
