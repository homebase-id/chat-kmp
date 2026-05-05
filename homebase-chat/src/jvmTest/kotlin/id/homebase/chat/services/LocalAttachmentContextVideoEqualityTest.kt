package id.homebase.chat.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the hand-rolled equals/hashCode on LocalAttachmentContext.Video.
 *
 * If a future field is added without updating equals/hashCode, the local-preview
 * path (which uses the equality to decide whether to remember + redraw a bitmap)
 * will silently miss state changes. Locking it down here is cheap insurance.
 */
class LocalAttachmentContextVideoEqualityTest {

    private fun video(
        thumbnailBytes: ByteArray = byteArrayOf(1, 2, 3),
        localFilePath: String = "/tmp/clip.mp4",
        aspectRatio: Float? = 16f / 9f,
        trimStartMs: Long? = null,
        trimEndMs: Long? = null,
        durationMs: Long? = null,
    ) = LocalAttachmentContext.Video(
        thumbnailBytes, localFilePath, aspectRatio, trimStartMs, trimEndMs, durationMs,
    )

    @Test
    fun identicalContents_areEqual_andShareHashCode() {
        val a = video()
        val b = video()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun thumbnailBytes_useContentEquals_notIdentity() {
        // Two distinct ByteArray instances with identical content must compare equal.
        // If equals() ever degrades to == on the array, every recomposition that
        // copies the bytes will report a false inequality.
        val bytesA = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val bytesB = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertFalse(bytesA === bytesB)
        assertEquals(video(thumbnailBytes = bytesA), video(thumbnailBytes = bytesB))
    }

    @Test
    fun thumbnailBytes_differingContent_notEqual() {
        val a = video(thumbnailBytes = byteArrayOf(1, 2, 3))
        val b = video(thumbnailBytes = byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun localFilePath_difference_notEqual() {
        assertNotEquals(video(localFilePath = "/a"), video(localFilePath = "/b"))
    }

    @Test
    fun aspectRatio_difference_notEqual() {
        assertNotEquals(video(aspectRatio = 1f), video(aspectRatio = 2f))
        assertNotEquals(video(aspectRatio = null), video(aspectRatio = 1f))
    }

    @Test
    fun trimStart_difference_notEqual() {
        assertNotEquals(video(trimStartMs = null), video(trimStartMs = 1_000))
        assertNotEquals(video(trimStartMs = 1_000), video(trimStartMs = 2_000))
    }

    @Test
    fun trimEnd_difference_notEqual() {
        assertNotEquals(video(trimEndMs = null), video(trimEndMs = 5_000))
        assertNotEquals(video(trimEndMs = 4_000), video(trimEndMs = 5_000))
    }

    @Test
    fun duration_difference_notEqual() {
        assertNotEquals(video(durationMs = null), video(durationMs = 30_000))
        assertNotEquals(video(durationMs = 30_000), video(durationMs = 60_000))
    }

    @Test
    fun reflexivity() {
        val a = video(trimStartMs = 1, trimEndMs = 2, durationMs = 3)
        assertTrue(a == a)
    }
}
