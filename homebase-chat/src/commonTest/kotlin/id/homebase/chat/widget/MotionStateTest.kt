package id.homebase.chat.widget

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.serialization.Base64Bytes
import id.homebase.chat.conversationlist.FullScreenOverlay
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MotionStateTest {
    private val clip = PayloadDescriptor(key = "photo_live", contentType = "video/mp4", iv = Base64Bytes(ByteArray(16) { 7 }))
    private val viewer = FullScreenOverlay.ViewMessageData(
        messageId = Uuid.random(),
        title = "",
        userDate = Instant.fromEpochMilliseconds(0),
        content = "",
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        payloads = listOf(PayloadDescriptor(key = "photo_main", contentType = "image/jpeg")),
        keyHeader = KeyHeader.newRandom16(),
        selectedPayloadKey = "photo_main",
        motionClips = mapOf("photo_main" to clip),
    )

    @Test
    fun theClipIsReadFromTheViewersFileWithItsOwnIv() {
        val player = viewer.motionPlayer(clip)
        assertEquals(viewer.fileId, player.fileId)
        assertEquals("photo_live", player.payloadKey)
        assertContentEquals(ByteArray(16) { 7 }, player.keyHeader.iv)
        assertContentEquals(viewer.keyHeader.aesKey.unsafeBytes, player.keyHeader.aesKey.unsafeBytes)
    }

    @Test
    fun aClipShowsFromItsFirstFrameAndHandsBackOnce() {
        val motion = MotionState(viewer.motionPlayer(clip), play = true)
        assertTrue(motion.playing)
        assertFalse(motion.visible, "the still shows while the clip loads")
        assertFalse(motion.mounted, "nothing plays before the preload is done")
        motion.preloaded = true
        assertTrue(motion.mounted)
        motion.onFirstFrame()
        assertTrue(motion.visible)
        motion.stop()
        assertFalse(motion.playing || motion.visible)
        motion.onFirstFrame()
        assertFalse(motion.visible, "a late first frame after a touch doesn't bring it back")
        assertFalse(MotionState(viewer.motionPlayer(clip), play = false).playing, "a neighbouring page doesn't play")
    }
}
