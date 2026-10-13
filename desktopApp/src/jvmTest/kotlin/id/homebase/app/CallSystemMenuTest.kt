package id.homebase.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import id.homebase.core.calling.DesktopCallTrayItem
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

    private val connected = listOf(DesktopCallTrayItem.Status("Samwise Gamgee", "01:23"), DesktopCallTrayItem.Mute(true), DesktopCallTrayItem.HangUp)

    @Test
    fun theMenuTextFollowsTheTimerOnlyEveryFewSecondsButAMuteChangeLandsAtOnce() = runDesktopComposeUiTest(300, 150) {
        val items = mutableStateOf(listOf(DesktopCallTrayItem.Status("Sam", "00:01"), DesktopCallTrayItem.Mute(false), DesktopCallTrayItem.HangUp))
        var shown: List<DesktopCallTrayItem> = emptyList()
        setContent { shown = rememberSettledCallItems(items.value) }
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(50)
        assertEquals("00:01", (shown[0] as DesktopCallTrayItem.Status).elapsed)

        for (second in 2..10) {
            items.value = listOf(DesktopCallTrayItem.Status("Sam", "00:%02d".format(second)), DesktopCallTrayItem.Mute(false), DesktopCallTrayItem.HangUp)
            mainClock.advanceTimeBy(1_000)
        }
        assertEquals("00:01", (shown[0] as DesktopCallTrayItem.Status).elapsed, "a ticking timer must not rebuild the menu")

        items.value = listOf(DesktopCallTrayItem.Status("Sam", "00:11"), DesktopCallTrayItem.Mute(true), DesktopCallTrayItem.HangUp)
        mainClock.advanceTimeBy(100)
        assertEquals(DesktopCallTrayItem.Mute(true), shown[1])

        mainClock.advanceTimeBy(15_000)
        assertEquals("00:11", (shown[0] as DesktopCallTrayItem.Status).elapsed)
    }

    @Test
    fun theTimerAloneNeverChangesTheMenuStructure() {
        assertEquals(withoutTimer(DesktopCallTrayItem.Status("Sam", "00:01")), withoutTimer(DesktopCallTrayItem.Status("Sam", "09:59")))
        assertEquals(false, withoutTimer(DesktopCallTrayItem.Status("Sam", "00:01")) == withoutTimer(DesktopCallTrayItem.Status("Frodo", "00:01")))
        assertEquals(DesktopCallTrayItem.Mute(true), withoutTimer(DesktopCallTrayItem.Mute(true)))
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
