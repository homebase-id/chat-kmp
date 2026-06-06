package id.homebase.core.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the in-house Skia animated decoder on the JVM (its code lives in the
 * shared skiaMain source set, so jvmMain — and therefore jvmTest — sees it).
 *
 * The two fixtures are hand-built minimal GIF89a files:
 *  - [ANIMATED_GIF_2_FRAMES]: 2x1, two frames (500ms red, 750ms blue), Netscape
 *    loop-forever block.
 *  - [STATIC_GIF_1_FRAME]: 2x1, a single frame, no loop block.
 */
@OptIn(ExperimentalEncodingApi::class)
class AnimatedSkiaDecoderTest {

    private val factory = AnimatedSkiaDecoder.Factory()
    private val options = Options(PlatformContext.INSTANCE)
    private val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()

    private fun sourceResult(bytes: ByteArray, mimeType: String? = null): SourceFetchResult {
        val buffer = Buffer().write(bytes)
        val source = ImageSource(buffer, FileSystem.SYSTEM)
        return SourceFetchResult(source, mimeType, DataSource.MEMORY)
    }

    // --- isAnimatableSource gate ---

    @Test
    fun `isAnimatableSource true for gif mimeType`() {
        assertTrue(isAnimatableSource(sourceResult(ByteArray(16), mimeType = "image/gif")))
    }

    @Test
    fun `isAnimatableSource true for webp mimeType`() {
        assertTrue(isAnimatableSource(sourceResult(ByteArray(16), mimeType = "image/webp")))
    }

    @Test
    fun `isAnimatableSource true for gif magic bytes with null mimeType`() {
        val gif = Base64.decode(ANIMATED_GIF_2_FRAMES)
        assertTrue(isAnimatableSource(sourceResult(gif, mimeType = null)))
    }

    @Test
    fun `isAnimatableSource true for webp magic bytes with null mimeType`() {
        // RIFF????WEBP header (12 bytes) is enough for the container sniff.
        val webp = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // RIFF
            0x10, 0x00, 0x00, 0x00, // chunk size (ignored)
            0x57, 0x45, 0x42, 0x50, // WEBP
        )
        assertTrue(isAnimatableSource(sourceResult(webp, mimeType = null)))
    }

    @Test
    fun `isAnimatableSource false for jpeg mimeType`() {
        assertFalse(isAnimatableSource(sourceResult(ByteArray(16), mimeType = "image/jpeg")))
    }

    @Test
    fun `isAnimatableSource false for png bytes with null mimeType`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        assertFalse(isAnimatableSource(sourceResult(png, mimeType = null)))
    }

    // --- Factory ---

    @Test
    fun `factory returns decoder for gif source`() {
        val gif = Base64.decode(ANIMATED_GIF_2_FRAMES)
        assertNotNull(factory.create(sourceResult(gif, "image/gif"), options, imageLoader))
    }

    @Test
    fun `factory returns null for jpeg source`() {
        assertNull(factory.create(sourceResult(ByteArray(16), "image/jpeg"), options, imageLoader))
    }

    // --- decode(): animated vs static ---

    @Test
    fun `decode returns animating image for multi-frame gif`() = runTest {
        val gif = Base64.decode(ANIMATED_GIF_2_FRAMES)
        val decoder = AnimatedSkiaDecoder(sourceResult(gif, "image/gif").source, options)
        val result = decoder.decode()
        assertNotNull(result, "multi-frame GIF should decode")
        val image = result.image
        assertTrue(image is SkiaAnimatedImage, "expected SkiaAnimatedImage, got ${image::class}")
        assertTrue(image.animatable, "2-frame GIF must be animatable")
        assertEquals(2, image.width)
        assertEquals(1, image.height)
    }

    @Test
    fun `decode returns null for single-frame gif so coil falls back to static decoder`() = runTest {
        val gif = Base64.decode(STATIC_GIF_1_FRAME)
        val decoder = AnimatedSkiaDecoder(sourceResult(gif, "image/gif").source, options)
        assertNull(decoder.decode(), "single-frame GIF must defer to default Skia decoder")
    }

    @Test
    fun `decode returns null for empty bytes`() = runTest {
        val decoder = AnimatedSkiaDecoder(sourceResult(ByteArray(0), "image/gif").source, options)
        assertNull(decoder.decode())
    }

    // --- frame timeline math (frameForElapsed) ---

    @Test
    fun `frameForElapsed maps elapsed time across frames and loops`() = runTest {
        val gif = Base64.decode(ANIMATED_GIF_2_FRAMES)
        val image = AnimatedSkiaDecoder(sourceResult(gif, "image/gif").source, options)
            .decode()!!.image as SkiaAnimatedImage

        // Frame 0 spans [0,500), frame 1 spans [500,1250); loops forever.
        assertEquals(0, image.frameForElapsed(0))
        assertEquals(0, image.frameForElapsed(499))
        assertEquals(1, image.frameForElapsed(500))
        assertEquals(1, image.frameForElapsed(1249))
        // Loop wrap: 1250ms == start of next loop -> frame 0 again.
        assertEquals(0, image.frameForElapsed(1250))
        assertEquals(1, image.frameForElapsed(1750))
        // Loop-forever GIF never returns null (never "finished").
        assertNotNull(image.frameForElapsed(10_000))
    }

    private companion object {
        // 2x1 GIF89a, 2 frames: 500ms red, 750ms blue, Netscape loop-forever.
        const val ANIMATED_GIF_2_FRAMES =
            "R0lGODlhAgABAPAAAP8AAAAA/yH/C05FVFNDQVBFMi4wAwEAAAAh+QQAMgAAACwAAAAAAgABAAAC" +
                "AgQKACH5BABLAAAALAAAAAACAAEAAAICTAoAOw=="

        // 2x1 GIF89a, single frame (no loop block).
        const val STATIC_GIF_1_FRAME =
            "R0lGODlhAgABAPAAAP8AAAAA/yH5BAAAAAAALAAAAAACAAEAAAICBAoAOw=="
    }
}
