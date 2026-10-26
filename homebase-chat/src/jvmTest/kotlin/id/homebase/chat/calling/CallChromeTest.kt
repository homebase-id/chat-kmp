package id.homebase.chat.calling

import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CallChromeTest {
    private val video = CallUiState(phase = CallPhase.Connected, localVideo = true, remoteVideo = true)

    @Test
    fun onlyAConnectedPhoneVideoCallWithNothingOpenAndNoScreenReaderHidesItsControls() {
        assertTrue(chromeAutoHideEligible(video, large = false, held = false, screenReader = false))
        assertFalse(chromeAutoHideEligible(video.copy(localVideo = false, remoteVideo = false), large = false, held = false, screenReader = false))
        assertFalse(chromeAutoHideEligible(video, large = true, held = false, screenReader = false))
        assertFalse(chromeAutoHideEligible(video, large = false, held = true, screenReader = false))
        assertFalse(chromeAutoHideEligible(video, large = false, held = false, screenReader = true))
        for (phase in listOf(
            CallPhase.Outgoing, CallPhase.Incoming, CallPhase.Connecting, CallPhase.Reconnecting,
            CallPhase.Ended(CallEndReason.Hangup(remote = true)),
        )) {
            assertFalse(chromeAutoHideEligible(video.copy(phase = phase), large = false, held = false, screenReader = false), "$phase")
        }
    }

    @Test
    fun controlsFadeAfterIdleAndATouchRestartsTheTimer() = runTest {
        val chrome = CallChromeState()
        val job = launch { chrome.run(eligible = true) }
        runCurrent()
        advanceTimeBy(ChromeIdleMs - 1)
        assertTrue(chrome.visible)
        chrome.onTouch()
        advanceTimeBy(ChromeIdleMs - 1)
        assertTrue(chrome.visible, "a touch pushes the fade back")
        advanceTimeBy(2)
        assertFalse(chrome.visible)
        job.cancel()
    }

    @Test
    fun aTapOnTheVideoTogglesTheControlsAndShowingThemRestartsTheTimer() = runTest {
        val chrome = CallChromeState()
        val job = launch { chrome.run(eligible = true) }
        runCurrent()
        chrome.onTapVideo()
        assertFalse(chrome.visible, "tap hides")
        chrome.onTapVideo()
        runCurrent()
        assertTrue(chrome.visible, "tap shows")
        advanceTimeBy(ChromeIdleMs + 1)
        assertFalse(chrome.visible, "and they fade again")
        job.cancel()
    }

    @Test
    fun anIneligibleCallKeepsItsControlsWhateverIsTapped() = runTest {
        val chrome = CallChromeState()
        val job = launch { chrome.run(eligible = true) }
        runCurrent()
        advanceTimeBy(ChromeIdleMs + 1)
        assertFalse(chrome.visible)
        job.cancel()
        chrome.run(eligible = false)
        assertTrue(chrome.visible, "losing eligibility brings them back")
        chrome.onTapVideo()
        advanceTimeBy(ChromeIdleMs * 2)
        assertTrue(chrome.visible)
    }

    @Test
    fun selfViewKeepsClearOfTheDockAndTopBarAndMovesIntoTheFreedSpace() {
        val shown = previewReserve(chromeVisible = true, large = false, compact = false)
        val hidden = previewReserve(chromeVisible = false, large = false, compact = false)
        assertEquals(PreviewMargin, hidden.top)
        assertEquals(PreviewMargin, hidden.bottom)
        assertTrue(shown.top > hidden.top && shown.bottom > hidden.bottom)
        // Phone dock: 16 bottom margin + 16 padding + 64 toggle + 6 + 16 label + 16 gap + 64 end button + 16 padding.
        val dock = 214.dp
        assertTrue(shown.bottom >= dock + PreviewMargin / 2, "self-view clears the dock: ${shown.bottom}")
        // Top bar: 8 padding + 48 button.
        assertTrue(shown.top >= 56.dp + PreviewMargin, "self-view clears the top bar: ${shown.top}")
        for (compact in listOf(false, true)) for (large in listOf(false, true)) {
            assertEquals(PreviewMargin, previewReserve(false, large, compact).top)
        }
    }
}
