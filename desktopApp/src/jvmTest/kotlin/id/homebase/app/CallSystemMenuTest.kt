package id.homebase.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import id.homebase.chat.calling.CallTrayItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CallSystemMenuTest {
    private val labels = CallMenuLabels(
        inCall = { "In call with $it" },
        inCallTimed = { name, elapsed -> "In call with $name · $elapsed" },
        mute = "Mute", unmute = "Unmute", hangUp = "Hang up",
    )

    @Test
    fun entriesCarryTheLabelAndTheActionTheyPerform() {
        val entries = callMenuEntries(
            listOf(CallTrayItem.Status("Sam", "01:05"), CallTrayItem.Mute(true), CallTrayItem.HangUp),
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
        assertEquals("In call with Sam", callMenuEntries(listOf(CallTrayItem.Status("Sam", null)), labels).single().label)
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

    private val connected = listOf(CallTrayItem.Status("Samwise Gamgee", "01:23"), CallTrayItem.Mute(true), CallTrayItem.HangUp)

    @Test
    fun theMenuFollowsTheTimerOnlyEveryFewSecondsButAMuteChangeLandsAtOnceWithoutRecomposingTheCaller() = runDesktopComposeUiTest(300, 150) {
        fun items(elapsed: String, muted: Boolean) = listOf(CallTrayItem.Status("Sam", elapsed), CallTrayItem.Mute(muted), CallTrayItem.HangUp)
        val flow = MutableStateFlow(items("00:01", muted = false))
        val passes = mutableListOf<List<CallMenuEntry>>()
        setContent {
            val entries = collectSettledCallEntries(flow, rememberApplicationLifecycleOwner())
            SideEffect { passes += entries }
        }
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(50)
        assertEquals(1, passes.size)
        assertEquals("In call with Sam · 00:01", passes.last()[0].label)

        for (second in 2..10) {
            flow.value = items("00:%02d".format(second), muted = false)
            mainClock.advanceTimeBy(1_000)
        }
        assertEquals(1, passes.size, "a ticking timer must not recompose the caller or rebuild the menu")

        flow.value = items("00:11", muted = true)
        mainClock.advanceTimeBy(100)
        assertEquals(2, passes.size)
        assertEquals("Unmute", passes.last()[1].label)

        flow.value = items("00:12", muted = true)
        mainClock.advanceTimeBy(15_000)
        assertEquals("In call with Sam · 00:12", passes.last()[0].label)
        assertEquals(3, passes.size)
    }

    @Test
    fun theTimerAloneNeverChangesTheMenuStructure() {
        assertEquals(withoutTimer(CallTrayItem.Status("Sam", "00:01")), withoutTimer(CallTrayItem.Status("Sam", "09:59")))
        assertEquals(false, withoutTimer(CallTrayItem.Status("Sam", "00:01")) == withoutTimer(CallTrayItem.Status("Frodo", "00:01")))
        assertEquals(CallTrayItem.Mute(true), withoutTimer(CallTrayItem.Mute(true)))
    }

    @Test
    fun trayAndDockEntriesRenderWithTheirRealLabels() {
        val outDir = File("build/call-shots").apply { mkdirs() }
        for (dark in listOf(false, true)) runDesktopComposeUiTest(320, 150) {
            setContent {
                HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
                        rememberCallMenuEntries(connected).forEach { entry ->
                            Text(entry.label, Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            onNodeWithText("In call with Samwise Gamgee · 01:23").assertExists()
            onNodeWithText("Unmute").assertExists()
            onNodeWithText("Hang Up").assertExists()
            mainClock.advanceTimeBy(400)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "tray-dock-menu-${if (dark) "dark" else "light"}.png"))
        }
    }
}
