package id.homebase.chat.widget

import id.homebase.chat.services.LocalAttachmentContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MediaPageSourceTest {

    private val iv = ByteArray(16) { it.toByte() }

    private fun imageContext(path: String) =
        LocalAttachmentContext.Image(localFilePath = path, aspectRatio = null)

    @Test
    fun `local image is preferred over the remote payload`() {
        val result = resolveMediaPageSource(imageContext("/tmp/photo.jpg"), rawIvPresent = true, decodedIv = iv)
        assertIs<MediaPageSource.LocalFile>(result)
        assertEquals("/tmp/photo.jpg", result.path)
    }

    @Test
    fun `local image is used even when the iv is absent`() {
        val result = resolveMediaPageSource(imageContext("/tmp/photo.jpg"), rawIvPresent = false, decodedIv = null)
        assertIs<MediaPageSource.LocalFile>(result)
        assertEquals("/tmp/photo.jpg", result.path)
    }

    @Test
    fun `remote is used when there is no local context and the iv decodes`() {
        val result = resolveMediaPageSource(null, rawIvPresent = true, decodedIv = iv)
        assertIs<MediaPageSource.Remote>(result)
        assertContentEquals(iv, result.iv)
    }

    @Test
    fun `a video local context is not treated as an image source`() {
        val videoContext = LocalAttachmentContext.Video(
            thumbnailBytes = ByteArray(0),
            localFilePath = "/tmp/clip.mp4",
            aspectRatio = null,
        )
        // A video's local file can't feed the image tiler, so we fall through to
        // the remote image payload instead.
        val result = resolveMediaPageSource(videoContext, rawIvPresent = true, decodedIv = iv)
        assertIs<MediaPageSource.Remote>(result)
    }

    @Test
    fun `pending when no local context and no iv yet`() {
        // A not-yet-uploaded payload has no IV — it's still arriving, not failed.
        assertEquals(
            MediaPageSource.Pending,
            resolveMediaPageSource(null, rawIvPresent = false, decodedIv = null),
        )
    }

    @Test
    fun `unavailable when the iv is present but does not decode`() {
        // A corrupt/undecodable IV is a genuine failure, distinct from pending.
        assertEquals(
            MediaPageSource.Unavailable,
            resolveMediaPageSource(null, rawIvPresent = true, decodedIv = null),
        )
    }
}
