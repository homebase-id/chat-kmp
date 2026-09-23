package id.homebase.core.camera

import id.homebase.core.camera.CameraPermissionEvent.Answered
import id.homebase.core.camera.CameraPermissionEvent.Checked
import id.homebase.core.camera.CameraPermissionEvent.Retry
import id.homebase.core.camera.CameraPermissionState.Checking
import id.homebase.core.camera.CameraPermissionState.Denied
import id.homebase.core.camera.CameraPermissionState.Granted
import id.homebase.core.camera.CameraPermissionState.PermanentlyDenied
import id.homebase.core.camera.CameraPermissionState.Requesting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraPermissionGateTest {
    private fun reduce(state: CameraPermissionState, event: CameraPermissionEvent) = CameraPermissionGate.reduce(state, event)

    @Test
    fun grantedOnOpenSkipsTheRequest() = assertEquals(Granted, reduce(Checking, Checked(true)))

    @Test
    fun notGrantedOnOpenRequests() = assertEquals(Requesting, reduce(Checking, Checked(false)))

    @Test
    fun answersMapToTheirStates() {
        assertEquals(Granted, reduce(Requesting, Answered(granted = true, permanentlyDenied = false)))
        assertEquals(Denied, reduce(Requesting, Answered(granted = false, permanentlyDenied = false)))
        assertEquals(PermanentlyDenied, reduce(Requesting, Answered(granted = false, permanentlyDenied = true)))
    }

    @Test
    fun retryRequestsAgainOnlyAfterASoftDenial() {
        assertEquals(Requesting, reduce(Denied, Retry))
        assertEquals(PermanentlyDenied, reduce(PermanentlyDenied, Retry))
    }

    @Test
    fun resumeAfterSettingsGrantUnlocks() = assertEquals(Granted, reduce(PermanentlyDenied, Checked(true)))

    @Test
    fun resumeWithoutGrantKeepsTheDenialScreen() {
        assertEquals(PermanentlyDenied, reduce(PermanentlyDenied, Checked(false)))
        assertEquals(Denied, reduce(Denied, Checked(false)))
        assertEquals(Requesting, reduce(Requesting, Checked(false)))
    }

    @Test
    fun revokedWhileOpenRequestsAgain() = assertEquals(Requesting, reduce(Granted, Checked(false)))

    @Test
    fun micIsAskedOnceThenReportedDenied() {
        val unknown = MicPermission(granted = false)
        assertTrue(unknown.needsAsking)
        assertFalse(unknown.isDenied)
        val answered = unknown.copy(askedThisSession = true)
        assertFalse(answered.needsAsking)
        assertTrue(answered.isDenied)
        assertFalse(MicPermission().needsAsking)
    }
}
