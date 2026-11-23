package id.homebase.core.widget

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class ScreenBackHandlerTest {

    private var enabled by mutableStateOf(true)
    private var backs = 0

    private val back = object : NavigationEventInput() {
        fun press() = dispatchOnBackCompleted()
    }

    private fun run(block: () -> Unit) = runSkikoComposeUiTest {
        setContent {
            @Suppress("DEPRECATION")
            val dispatcher = LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(back)
                onDispose { dispatcher.removeInput(back) }
            }
            ScreenBackHandler(enabled = enabled) { backs++ }
        }
        waitForIdle()
        block()
        waitForIdle()
    }

    @Test
    fun `system back runs the handler once while enabled`() {
        enabled = true
        backs = 0
        run { back.press() }
        assertEquals(1, backs)
    }

    @Test
    fun `system back is not consumed while disabled`() {
        enabled = false
        backs = 0
        run { back.press() }
        assertEquals(0, backs)
    }
}
