package id.homebase.core.haptics

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals

class HapticEventMappingTest {

    @Test
    fun selectionMapsToTextHandleMove() {
        assertEquals(HapticFeedbackType.TextHandleMove, HapticEvent.Selection.toComposeType())
    }

    @Test
    fun longPressMapsToLongPress() {
        assertEquals(HapticFeedbackType.LongPress, HapticEvent.LongPress.toComposeType())
    }

    @Test
    fun confirmMapsToConfirm() {
        assertEquals(HapticFeedbackType.Confirm, HapticEvent.Confirm.toComposeType())
    }
}
