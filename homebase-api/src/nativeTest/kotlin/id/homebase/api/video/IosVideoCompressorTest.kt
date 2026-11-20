@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.scratchDir
import id.homebase.api.image.ImageHeaderParser
import id.homebase.api.image.ImageSize
import id.homebase.api.media.MediaMetadataReader
import id.homebase.api.video.hls.HlsSegmenter
import id.homebase.api.video.hls.Mp4Movie
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.creationDate
import platform.AVFoundation.dateValue
import platform.CoreGraphics.CGColorSpaceUsesITUR_2100TF
import platform.CoreGraphics.CGImageGetColorSpace
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.Foundation.timeIntervalSince1970
import platform.posix.getenv

/** [IosVideoCompressor] and the poster decoder on the simulator's AVFoundation, with the clips Android's `Media3VideoCompressorTest` uses. */
class IosVideoCompressorTest {
    private companion object {
        const val CAPTURED_AT = 1_767_323_045L // 2026-01-02T03:04:05Z, the fixture's capture time
    }

    private val fileOps = IosVideoCompressor.fileOps
    private val clips = checkNotNull(getenv("TEST_VIDEOS")?.toKString()) { "TEST_VIDEOS isn't set: run through Gradle" }

    private val iphoneClips = checkNotNull(getenv("IPHONE_VIDEOS")?.toKString()) { "IPHONE_VIDEOS isn't set: run through Gradle" }

    private fun clip(name: String) = if (name.startsWith("iphone_")) "$iphoneClips/$name" else "$clips/$name"

    private suspend fun compressed(
        name: String,
        quality: VideoQuality = VideoQuality.STANDARD,
        keep: Boolean = false,
        trim: Pair<Long, Long>? = null,
        check: suspend (String) -> Unit,
    ) {
        val out = IosVideoCompressor.compress(clip(name), null, trim?.first, trim?.second, quality, keep)
        try {
            check(out)
        } finally {
            fileOps.deleteTempFile(out)
        }
    }

    private suspend fun movie(path: String) = readMoov(fileOps, path)?.let(Mp4Movie::parse)

    @Test
    fun aLandscape1080pVideoComesOutAt720pH264WithoutItsLocation() = runBlocking<Unit> {
        compressed("landscape_1080p_gps.mp4") { out ->
            val info = assertNotNull(IosVideoProber.probeVideo(out))
            assertEquals("h264", info.codec)
            assertEquals(1280 to 720, info.widthPx to info.heightPx)
            assertTrue(abs(IosVideoProber.getDurationMs(out) - 3_000) < 200, "duration ${IosVideoProber.getDurationMs(out)}")
            assertNull(MediaMetadataReader(fileOps).videoLocation(out), "a stripped video must not carry its location")
        }
    }

    @Test
    fun aPortraitVideoComesOutUprightWithItsPictureAndSegments() = runBlocking<Unit> {
        compressed("portrait_1080p.mp4") { out ->
            val info = assertNotNull(IosVideoProber.probeVideo(out))
            assertEquals(720 to 1280, info.widthPx to info.heightPx)
            assertNotNull(movie(out), "an MP4 our segmenter takes: H.264, AAC, no rotation flag")
            // A frame drawn off the canvas encodes to a few kB; the picture is hundreds.
            assertTrue(fileOps.getFileSize(out) > 200_000, "portrait output ${fileOps.getFileSize(out)} B looks blank")
            val segmented = assertNotNull(HlsSegmenter.segmentAndEncrypt(fileOps, out, KeyHeader.newRandom16()))
            fileOps.deleteTempFile(segmented.playlistPath.substringBeforeLast('/'))
        }
    }

    @Test
    fun anUploadsPosterIsTheUprightSdrFrameAtTheVideosOwnSizeAndAPreviewIsSmaller() = runBlocking<Unit> {
        val upright = listOf(
            "landscape_1080p_gps.mp4" to ImageSize(1920, 1080),
            "portrait_1080p.mp4" to ImageSize(1080, 1920),
            "iphone_hevc.mov" to ImageSize(1080, 1920),
            "iphone_spatial.mov" to ImageSize(2160, 3840),
            "hdr10_720p_short.mp4" to ImageSize(1280, 720),
        )
        for ((name, size) in upright) {
            val full = assertNotNull(VideoThumbnailService.extractPosterFrame(clip(name), fullSize = true), name)
            assertEquals(size, ImageHeaderParser.parse(full), "$name: an upload's thumbnails are made from the full frame")
            assertFalse(usesHdrTransfer(full), "$name: the poster is SDR")
            val previewBytes = assertNotNull(VideoThumbnailService.extractPosterFrame(clip(name)), name)
            val preview = assertNotNull(ImageHeaderParser.parse(previewBytes), name)
            val edge = maxOf(preview.pixelWidth, preview.pixelHeight)
            assertTrue(edge >= 640 && edge < maxOf(size.pixelWidth, size.pixelHeight), "$name: a preview is $edge px")
        }
    }

    @OptIn(BetaInteropApi::class)
    private fun usesHdrTransfer(jpeg: ByteArray): Boolean = jpeg.usePinned {
        val data = NSData.create(bytes = it.addressOf(0), length = jpeg.size.toULong())
        val image = checkNotNull(UIImage.imageWithData(data)?.CGImage) { "the poster isn't an image" }
        CGColorSpaceUsesITUR_2100TF(CGImageGetColorSpace(image))
    }

    // Portrait 1080p and 4K HEVC from iPhones; the 4K one also has a spatial-audio track and metadata tracks.
    @Test
    fun iphoneCapturesComeOutAsUprightH264WithAac() = runBlocking<Unit> {
        for (name in listOf("iphone_hevc.mov", "iphone_spatial.mov")) {
            compressed(name) { out ->
                val info = assertNotNull(IosVideoProber.probeVideo(out), name)
                assertEquals("h264", info.codec, name)
                assertEquals(720 to 1280, info.widthPx to info.heightPx, name)
                assertNotEquals(true, info.isHdr, name)
                assertNotNull(movie(out)?.audio, "$name: AAC audio in an MP4 our segmenter takes")
            }
        }
    }

    @Test
    fun keepingMetadataKeepsTheLocationAndCaptureTime() = runBlocking<Unit> {
        compressed("landscape_1080p_gps.mp4", keep = true) { out ->
            val location = assertNotNull(MediaMetadataReader(fileOps).videoLocation(out))
            assertTrue(abs(location.latitude - 59.9139) < 1e-4 && abs(location.longitude - 10.7522) < 1e-4, "location $location")
            assertEquals(CAPTURED_AT, captureTime(out))
        }
        compressed("landscape_1080p_gps.mp4") { out ->
            assertNotEquals(CAPTURED_AT, captureTime(out), "a stripped video must not carry its capture time")
        }
    }

    private fun captureTime(path: String) =
        AVURLAsset.URLAssetWithURL(fileUrl(path), null).creationDate?.dateValue?.timeIntervalSince1970?.toLong()

    @Test
    fun eachQualitySettingGivesItsSizeAndAboutItsBitrate() = runBlocking<Unit> {
        val expected = mapOf(VideoQuality.STANDARD to (1280 to 720), VideoQuality.HIGH to (1920 to 1080), VideoQuality.LOW to (854 to 480))
        for ((quality, size) in expected) {
            compressed("landscape_1440p.mp4", quality) { out ->
                val info = assertNotNull(IosVideoProber.probeVideo(out))
                assertEquals(size, info.widthPx to info.heightPx, "$quality")
                val targetBytes = quality.targets().videoBitrateBps.toLong() * IosVideoProber.getDurationMs(out) / 8000
                val bytes = fileOps.getFileSize(out)
                assertTrue(bytes < targetBytes * 3 / 2, "$quality: $bytes B against a target of $targetBytes B")
            }
        }
    }

    @Test
    fun aacAudioIsCopiedAndOtherAudioBecomesAac() = runBlocking<Unit> {
        for (name in listOf("sample.mp4", "opus_audio.mp4")) {
            compressed(name) { out ->
                assertNotNull(movie(out)?.audio, "$name: AAC audio in the output")
            }
        }
    }

    @Test
    fun aTrimKeepsOnlyItsRange() = runBlocking<Unit> {
        compressed("landscape_1080p_gps.mp4", trim = 500L to 2_000L) { out ->
            val ms = IosVideoProber.getDurationMs(out)
            assertTrue(abs(ms - 1_500) < 150, "trimmed duration $ms")
        }
    }

    @Test
    fun hdrComesOutAsEightBitSdr() = runBlocking<Unit> {
        assertEquals(true, IosVideoProber.probeVideo(clip("hdr10_720p_short.mp4"))?.isHdr, "the fixture is HDR")
        compressed("hdr10_720p_short.mp4") { out ->
            val info = assertNotNull(IosVideoProber.probeVideo(out))
            assertEquals(false, info.isHdr)
            assertNotEquals(10, info.bitDepth)
        }
    }

    @Test
    fun cancellingStopsWithinASecondAndLeavesNothing() = runBlocking<Unit> {
        val work = fileOps.scratchDir(AppCacheDirs.MEDIA_WORK)
        val before = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(work, null)?.size ?: 0)
        val started = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            IosVideoCompressor.compress(clip("landscape_1440p.mp4"), { if (it > 0f) started.complete(Unit) }, null, null, VideoQuality.HIGH, false)
        }
        started.await()
        val mark = TimeSource.Monotonic.markNow()
        job.cancel()
        runCatching { job.await() }
        assertTrue(mark.elapsedNow().inWholeMilliseconds < 1_000, "cancel took ${mark.elapsedNow()}")
        assertEquals(before, (NSFileManager.defaultManager.contentsOfDirectoryAtPath(work, null)?.size ?: 0), "a cancelled compress leaves no output")
    }

    @Test
    fun onlyFormatsAvFoundationOpensAreCompressed() {
        assertTrue(IosVideoProber.canRead("video/mp4"))
        assertTrue(IosVideoProber.canRead("video/quicktime"))
        assertTrue(IosVideoProber.canRead("video/x-msvideo"))
        assertFalse(IosVideoProber.canRead("video/x-matroska"))
        assertFalse(IosVideoProber.canRead("video/webm"))
        assertFalse(IosVideoProber.canRead("image/jpeg"))
    }
}
