package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class KeepListEndInViewTest {

    private fun barAboveListAtEnd(keepEnd: Boolean, check: (LazyListState) -> Unit) = runComposeUiTest {
        val state = LazyListState(firstVisibleItemIndex = 49)
        var barShown by mutableStateOf(false)
        var lastRowHeight by mutableStateOf(50)
        var rowAboveHeight by mutableStateOf(50)
        setContent {
            Column(Modifier.size(300.dp, 400.dp)) {
                if (barShown) Box(Modifier.fillMaxWidth().height(80.dp))
                LazyColumn(Modifier.weight(1f), state = state) {
                    items(50) {
                        val height = when (it) {
                            49 -> lastRowHeight
                            48 -> rowAboveHeight
                            else -> 50
                        }
                        Box(Modifier.fillMaxWidth().height(height.dp))
                    }
                }
            }
            if (keepEnd) KeepListEndInView(state, Unit)
        }
        waitForIdle()
        assertFalse(state.canScrollForward)
        rowAboveHeight = 76
        waitForIdle()
        barShown = true
        waitForIdle()
        lastRowHeight = 90
        waitForIdle()
        check(state)
    }

    @Test
    fun barAboveListPushesTheNewestRowOutOfViewByDefault() =
        barAboveListAtEnd(keepEnd = false) { assertTrue(it.canScrollForward) }

    @Test
    fun keepsTheNewestRowInViewWhenRowsGrowAndTheBarExpands() =
        barAboveListAtEnd(keepEnd = true) { assertFalse(it.canScrollForward) }
}
