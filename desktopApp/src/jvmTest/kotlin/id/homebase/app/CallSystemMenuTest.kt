package id.homebase.app

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import id.homebase.core.calling.DesktopCallTrayItem
import kotlin.test.Test
import kotlin.test.assertEquals

class CallSystemMenuTest {
    private val labels = CallMenuLabels(
        inCall = { "In call with $it" },
        inCallTimed = { name, elapsed -> "In call with $name · $elapsed" },
        mute = "Mute", unmute = "Unmute", hangUp = "Hang up",
    )

    @Test
    fun entriesCarryTheLabelAndTheActionTheyPerform() {
        val entries = callMenuEntries(
            listOf(DesktopCallTrayItem.Status("Sam", "01:05"), DesktopCallTrayItem.Mute(true), DesktopCallTrayItem.HangUp),
            labels,
        )
        assertEquals(
            listOf(
                CallMenuEntry(CallMenuAction.OpenCall, "In call with Sam · 01:05"),
                CallMenuEntry(CallMenuAction.ToggleMute, "Unmute"),
                CallMenuEntry(CallMenuAction.HangUp, "Hang up"),
            ),
            entries,
        )
    }

    @Test
    fun aCallNotYetConnectedShowsNoTimer() {
        assertEquals("In call with Sam", callMenuEntries(listOf(DesktopCallTrayItem.Status("Sam", null)), labels).single().label)
    }

    @Test
    fun noCallMeansNoEntries() {
        assertEquals(emptyList(), callMenuEntries(emptyList(), labels))
    }

    @Test
    fun theRingWindowSitsInTheTopRightOfTheUsableScreenArea() {
        val area = DpRect(0.dp, 25.dp, 1440.dp, 875.dp)
        assertEquals(WindowPosition.Absolute(1064.dp, 41.dp), ringWindowPosition(area, DpSize(360.dp, 200.dp)))
        val second = DpRect(1440.dp, 0.dp, 3360.dp, 1080.dp)
        assertEquals(WindowPosition.Absolute(2984.dp, 16.dp), ringWindowPosition(second, DpSize(360.dp, 200.dp)))
    }
}
