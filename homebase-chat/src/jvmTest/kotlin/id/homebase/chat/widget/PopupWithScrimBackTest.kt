package id.homebase.chat.widget

import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.LocalCompatNavigationEventDispatcherOwner
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInput
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class PopupWithScrimBackTest {

    private var mode by mutableStateOf(MessagePopupMode.All)
    private var screenBacks = 0

    private val back = object : NavigationEventInput() {
        fun press() = dispatchOnBackCompleted()
    }

    @Test
    fun `back closes the long-press menu and stays in the conversation`() = runSkikoComposeUiTest {
        setContent {
            @Suppress("DEPRECATION")
            val dispatcher = LocalCompatNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(back)
                onDispose { dispatcher.removeInput(back) }
            }
            @Suppress("DEPRECATION")
            androidx.compose.ui.backhandler.BackHandler { screenBacks++ }
            MaterialTheme {
                val transition = updateTransition(mode, label = "test")
                if (transition.shownMode != MessagePopupMode.None) {
                    PopupWithScrim(transition, onDismissRequest = { mode = MessagePopupMode.None }) {
                        Box(Modifier.size(40.dp))
                    }
                }
            }
        }
        waitForIdle()

        runOnIdle { back.press() }
        waitForIdle()

        assertEquals<MessagePopupMode>(MessagePopupMode.None, mode)
        assertEquals(0, screenBacks)
    }
}
