package id.homebase.chat.calling

import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.pipAspectRatio
import id.homebase.chat.services.calling.shouldLeavePictureInPicture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PictureInPictureTest {
    @Test
    fun eligibleOnlyForConnectedVideoCalls() {
        assertTrue(CallUiState(phase = CallPhase.Connected, remoteVideo = true).pipEligible)
        assertTrue(CallUiState(phase = CallPhase.Reconnecting, localVideo = true).pipEligible)
        assertFalse(CallUiState(phase = CallPhase.Connected).pipEligible)
        assertFalse(CallUiState(phase = CallPhase.Connecting, remoteVideo = true).pipEligible)
        assertFalse(CallUiState(phase = CallPhase.Incoming, remoteVideo = true).pipEligible)
        assertFalse(CallUiState().pipEligible)
    }

    @Test
    fun aspectRatioFollowsRemoteFrameWithinSystemLimits() {
        assertEquals(9 to 16, pipAspectRatio(null))
        assertEquals(9 to 16, pipAspectRatio(0 to 0))
        assertEquals(1280 to 720, pipAspectRatio(1280 to 720))
        assertEquals(720 to 1280, pipAspectRatio(720 to 1280))
        assertEquals(239 to 100, pipAspectRatio(4000 to 100))
        assertEquals(100 to 239, pipAspectRatio(100 to 4000))
    }

    @Test
    fun screenStaysAwakeOnlyWhileCallIsInProgress() {
        assertTrue(CallUiState(phase = CallPhase.Connecting).keepScreenAwake)
        assertTrue(CallUiState(phase = CallPhase.Connected).keepScreenAwake)
        assertTrue(CallUiState(phase = CallPhase.Reconnecting).keepScreenAwake)
        assertFalse(CallUiState(phase = CallPhase.Incoming).keepScreenAwake)
        assertFalse(CallUiState().keepScreenAwake)
    }

    @Test
    fun leavesPipWhenCallEndsWhileInPip() {
        val ended = CallUiState(phase = CallPhase.Ended(CallEndReason.Hangup(remote = false)), remoteVideo = true)
        assertTrue(shouldLeavePictureInPicture(inPip = true, eligible = ended.pipEligible))
        assertFalse(shouldLeavePictureInPicture(inPip = true, eligible = CallUiState(phase = CallPhase.Connected, remoteVideo = true).pipEligible))
        assertFalse(shouldLeavePictureInPicture(inPip = false, eligible = false))
    }
}
