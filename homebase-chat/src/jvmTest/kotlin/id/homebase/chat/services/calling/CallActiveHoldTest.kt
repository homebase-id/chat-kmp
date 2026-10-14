package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class CallActiveHoldTest {
    @Test
    fun reportsOnlyActiveTransitions() = runTest {
        val states = flowOf(
            CallState(),
            CallState(phase = CallPhase.Connecting, callId = "c"),
            CallState(phase = CallPhase.Connected, callId = "c"),
            CallState(phase = CallPhase.Ended(CallEndReason.Cancelled), callId = "c"),
        )
        assertEquals(listOf(false, true, false), states.callActiveChanges().toList())
    }
}
