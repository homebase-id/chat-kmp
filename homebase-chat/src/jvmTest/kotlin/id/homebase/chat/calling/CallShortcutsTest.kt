package id.homebase.chat.calling

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.FakeVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallShortcutsTest {
    private val camera = CallVideos(FakeVideo("FB"), FakeVideo("SG"))

    private fun ui(phase: CallPhase, videos: CallVideos = camera, video: Boolean = true) = CallUiState(
        phase = phase,
        callId = "c",
        peer = "p",
        direction = CallDirection.Outgoing,
        localVideo = video,
        remoteVideo = video,
        videos = videos,
    )

    private fun press(
        key: Key,
        ui: CallUiState,
        command: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        type: KeyEventType = KeyEventType.KeyDown,
        textFocused: Boolean = false,
        codePoint: Int = 0,
    ) = callShortcutFor(CallKeyInput(key, type, command, shift, alt, codePoint), ui, textFocused)

    private val connected = ui(CallPhase.Connected)

    @Test
    fun commandDAndEAreMuteAndCameraWhileTheCallIsLive() {
        for (phase in listOf(CallPhase.Connected, CallPhase.Reconnecting, CallPhase.Connecting, CallPhase.Outgoing)) {
            assertEquals(CallShortcut.ToggleMute, press(Key.D, ui(phase), command = true), "$phase")
            assertEquals(CallShortcut.ToggleCamera, press(Key.E, ui(phase), command = true), "$phase")
        }
    }

    @Test
    fun commandShortcutsDoNothingWhileRingingOrEnded() {
        for (phase in listOf(CallPhase.Incoming, CallPhase.Idle, CallPhase.Ended(CallEndReason.Cancelled))) {
            assertNull(press(Key.D, ui(phase), command = true), "$phase")
            assertNull(press(Key.E, ui(phase), command = true), "$phase")
            assertNull(press(Key.M, ui(phase), command = true, shift = true), "$phase")
            assertNull(press(Key.M, ui(phase)), "$phase")
        }
    }

    @Test
    fun aVoiceCallCanTurnItsCameraOnOnceItIsInProgressButNotWhileDialling() {
        val voice = ui(CallPhase.Connected, CallVideos(), video = false)
        assertEquals(CallShortcut.ToggleCamera, press(Key.E, voice, command = true))
        assertEquals(CallShortcut.ToggleCamera, press(Key.V, voice))
        assertEquals(CallShortcut.ToggleMute, press(Key.D, voice, command = true))

        val dialling = ui(CallPhase.Outgoing, CallVideos(), video = false)
        assertNull(press(Key.E, dialling, command = true))
        assertNull(press(Key.V, dialling))
    }

    @Test
    fun bareMAndVStayButOnlyWithoutModifiersAndWithoutTextFocus() {
        assertEquals(CallShortcut.ToggleMute, press(Key.M, connected))
        assertEquals(CallShortcut.ToggleCamera, press(Key.V, connected))
        assertNull(press(Key.M, connected, textFocused = true))
        assertNull(press(Key.V, connected, textFocused = true))
        assertNull(press(Key.M, connected, alt = true))
        assertNull(press(Key.V, connected, shift = true))
    }

    @Test
    fun commandShiftMTogglesMiniAndPlainCommandMDoesNot() {
        assertEquals(CallShortcut.ToggleMini, press(Key.M, connected, command = true, shift = true))
        assertNull(press(Key.M, connected, command = true))
        assertNull(press(Key.D, connected, command = true, shift = true))
        assertNull(press(Key.D, connected, command = true, alt = true))
    }

    @Test
    fun escapeAndCommandWKeepTheCloseSemanticsInEveryPhaseAndEvenWithTextFocus() {
        for (phase in listOf(CallPhase.Incoming, CallPhase.Outgoing, CallPhase.Connected, CallPhase.Idle, CallPhase.Ended(CallEndReason.Cancelled))) {
            assertEquals(CallShortcut.Close, press(Key.Escape, ui(phase)), "$phase")
            assertEquals(CallShortcut.Close, press(Key.W, ui(phase), command = true), "$phase")
        }
        assertEquals(CallShortcut.Close, press(Key.Escape, connected, textFocused = true))
        assertNull(press(Key.W, connected))
    }

    @Test
    fun shiftQuestionMarkOpensTheSheetUnlessTyping() {
        assertEquals(CallShortcut.ShowShortcuts, press(Key.Slash, connected, shift = true))
        assertEquals(CallShortcut.ShowShortcuts, press(Key.Unknown, connected, shift = true, codePoint = '?'.code))
        assertEquals(CallShortcut.ShowShortcuts, press(Key.Slash, ui(CallPhase.Incoming), shift = true))
        assertNull(press(Key.Slash, connected, shift = true, textFocused = true))
        assertNull(press(Key.Slash, connected))
        assertNull(press(Key.Slash, connected, command = true, shift = true))
    }

    @Test
    fun commandShiftHHangsUpOnlyWhileTheCallIsLive() {
        assertEquals(CallShortcut.HangUp, press(Key.H, connected, command = true, shift = true))
        assertEquals(CallShortcut.HangUp, press(Key.H, connected, command = true, shift = true, textFocused = true))
        assertNull(press(Key.H, connected, command = true))
        assertNull(press(Key.H, ui(CallPhase.Incoming), command = true, shift = true))
    }

    @Test
    fun onlyKeyDownFires() {
        for (type in listOf(KeyEventType.KeyUp, KeyEventType.Unknown)) {
            assertNull(press(Key.D, connected, command = true, type = type))
            assertNull(press(Key.M, connected, type = type))
            assertNull(press(Key.Escape, connected, type = type))
        }
    }

    @Test
    fun theGateSwallowsTheSameShortcutArrivingTwiceButNotADifferentOne() {
        var now = 1_000L
        val gate = ShortcutGate { now }
        assertTrue(gate.accept(CallShortcut.ToggleMute))
        now += 20
        assertFalse(gate.accept(CallShortcut.ToggleMute))
        assertTrue(gate.accept(CallShortcut.ToggleCamera))
        now += 400
        assertTrue(gate.accept(CallShortcut.ToggleCamera))
    }

    @Test
    fun chromeOnlyAutoHidesInAConnectedVideoCall() {
        assertTrue(chromeAutoHideEligible(ui(CallPhase.Connected)))
        assertFalse(chromeAutoHideEligible(ui(CallPhase.Connected, CallVideos(), video = false)))
        for (phase in listOf(CallPhase.Incoming, CallPhase.Outgoing, CallPhase.Connecting, CallPhase.Reconnecting, CallPhase.Ended(CallEndReason.Cancelled))) {
            assertFalse(chromeAutoHideEligible(ui(phase)), "$phase")
        }
    }

    @Test
    fun layoutClassFollowsTheWindowSize() {
        assertEquals(CallTier.Minimal, desktopTier(400.dp, 300.dp))
        assertEquals(CallTier.Minimal, desktopTier(320.dp, 240.dp))
        assertEquals(CallTier.Minimal, desktopTier(479.dp, 700.dp))
        assertEquals(CallTier.Minimal, desktopTier(900.dp, 359.dp))
        assertEquals(CallTier.Compact, desktopTier(480.dp, 360.dp))
        assertEquals(CallTier.Compact, desktopTier(599.dp, 700.dp))
        assertEquals(CallTier.Compact, desktopTier(900.dp, 400.dp))
        assertEquals(CallTier.Medium, desktopTier(700.dp, 500.dp))
        assertEquals(CallTier.Expanded, desktopTier(960.dp, 640.dp))
    }
}
