package id.homebase.chat.widget

import id.homebase.chat.conversationlist.UploadStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadOverlayVisibilityTest {

    @Test
    fun sendingShowsOverlayOnlyWhenOnline() {
        // "Sending" = durably queued waiting for the network. Offline it never progresses
        // (airplane mode) and would spin forever, so it shows only the corner outbox icon.
        // Online it's a brief handoff to the active upload, so the spinner shows (#948).
        assertFalse(UploadStatus.Sending.showsMediaOverlay(isConnected = false))
        assertTrue(UploadStatus.Sending.showsMediaOverlay(isConnected = true))
    }

    @Test
    fun localWorkAndInFlightStatesAlwaysShowOverlay() {
        // Preparing = local prep (thumbnail/resize/compress/encrypt); Processing = video
        // transcode; Uploading = active transfer; Completed = brief tick. All show the
        // overlay regardless of connectivity — Preparing is real work even offline.
        for (connected in listOf(true, false)) {
            assertTrue(UploadStatus.Preparing.showsMediaOverlay(connected))
            assertTrue(UploadStatus.Processing(progress = 0.5f).showsMediaOverlay(connected))
            assertTrue(UploadStatus.Uploading(progress = 0.5f).showsMediaOverlay(connected))
            assertTrue(UploadStatus.Completed.showsMediaOverlay(connected))
        }
    }
}
