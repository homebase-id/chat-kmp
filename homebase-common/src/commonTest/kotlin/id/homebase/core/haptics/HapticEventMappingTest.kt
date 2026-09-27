package id.homebase.core.haptics

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals

class HapticEventMappingTest {

    @Test
    fun selectionMapsToSegmentFrequentTick() {
        assertEquals(HapticFeedbackType.SegmentFrequentTick, HapticEvent.Selection.toComposeType())
    }

    @Test
    fun tickMapsToSegmentTick() {
        assertEquals(HapticFeedbackType.SegmentTick, HapticEvent.Tick.toComposeType())
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
