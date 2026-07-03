package id.homebase.chat.widget

import id.homebase.chat.conversationlist.UploadStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadOverlayVisibilityTest {

    @Test
    fun stagedAndQueuedStatesHideOverlay() {
        // Offline-queued media stays on Sending forever; neither it nor Preparing may
        // draw the scrim + spinner (#948) — the outbox corner icon covers them.
        assertFalse(UploadStatus.Preparing.showsMediaOverlay())
        assertFalse(UploadStatus.Sending.showsMediaOverlay())
    }

    @Test
    fun inFlightAndCompletedStatesShowOverlay() {
        assertTrue(UploadStatus.Processing(progress = 0.5f).showsMediaOverlay())
        assertTrue(UploadStatus.Uploading(progress = 0.5f).showsMediaOverlay())
        assertTrue(UploadStatus.Completed.showsMediaOverlay())
    }
}
