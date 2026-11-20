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
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.creationDate
import platform.AVFoundation.dateValue
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorSpaceUsesITUR_2100TF
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageGetColorSpace
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.Foundation.timeIntervalSince1970
import platform.posix.getenv

private const val CAPTURED_AT = 1_767_323_045L // 2026-01-02T03:04:05Z, the fixture's capture time

/** [IosVideoCompressor] and the poster decoder on the simulator's AVFoundation, with the clips Android's `Media3VideoCompressorTest` uses. */
class IosVideoCompressorTest {
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

    private fun captureTime(path: String) =
        AVURLAsset.URLAssetWithURL(fileUrl(path), null).creationDate?.dateValue?.timeIntervalSince1970?.toLong()

    @Test
    fun aLandscape1080pVideoComesOutAt720pH264WithoutItsLocation() = runBlocking<Unit> {
        compressed("landscape_1080p_gps.mp4") { out ->
            val info = assertNotNull(IosVideoProber.probeVideo(out))
            assertEquals("h264", info.codec)
            assertEquals(1280 to 720, info.widthPx to info.heightPx)
            assertTrue(abs(IosVideoProber.getDurationMs(out) - 3_000) < 200, "duration ${IosVideoProber.getDurationMs(out)}")
            assertNull(MediaMetadataReader(fileOps).videoLocation(out), "a stripped video must not carry its location")
            assertNotEquals(CAPTURED_AT, captureTime(out), "a stripped video must not carry its capture time")
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
        // iphone_hevc.mov is an iPhone's HDR (HLG, 10-bit) capture, so its poster must come out SDR.
        val upright = listOf(
            "landscape_1080p_gps.mp4" to ImageSize(1920, 1080),
            "portrait_1080p.mp4" to ImageSize(1080, 1920),
            "iphone_hevc.mov" to ImageSize(1080, 1920),
            "iphone_spatial.mov" to ImageSize(2160, 3840),
            "hdr10_720p_short.mp4" to ImageSize(720, 1280),
        )
        val failures = mutableListOf<String>()
        for ((name, size) in upright) {
            val full = VideoThumbnailService.extractPosterFrame(clip(name), fullSize = true)
            when {
                full == null -> failures += "$name: no full-size poster (${posterError(name, fullSize = true)})"
                ImageHeaderParser.parse(full) != size -> failures += "$name: full-size poster ${ImageHeaderParser.parse(full)}, the video is $size"
                usesHdrTransfer(full) -> failures += "$name: the full-size poster isn't SDR"
            }
            val preview = VideoThumbnailService.extractPosterFrame(clip(name))?.let(ImageHeaderParser::parse)
            val edge = preview?.let { maxOf(it.pixelWidth, it.pixelHeight) }
            if (edge == null) {
                failures += "$name: no preview (${posterError(name, fullSize = false)})"
            } else if (edge < 640 || edge >= maxOf(size.pixelWidth, size.pixelHeight)) {
                failures += "$name: a preview is $edge px"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun aTrimStripHasEveryFrameUprightForSdrAndPqHdr() = runBlocking<Unit> {
        for (name in listOf("portrait_1080p.mp4", "hdr10_720p_short.mp4")) {
            val frames = VideoThumbnailService.extractThumbnailStrip(clip(name), IosVideoProber.getDurationMs(clip(name)), 4, 96).toList()
            assertEquals(4, frames.size, "$name: strip frames")
            for (frame in frames) {
                val size = assertNotNull(ImageHeaderParser.parse(frame.jpegBytes), name)
                assertTrue(size.pixelHeight == 96 && size.pixelWidth < 96, "$name: a strip frame is $size")
            }
        }
    }

    // White video from 0.5 s to 2.5 s, audio to 3.5 s: the composition draws black where there's no picture.
    @Test
    fun postersAndStripsComeFromTheVideosPicturesWhenItStartsLateAndEndsEarly() = runBlocking<Unit> {
        val name = "late_video_long_audio.mp4"
        val poster = assertNotNull(VideoThumbnailService.extractPosterFrame(clip(name), fullSize = true), "no poster")
        assertTrue(brightness(poster) > 200, "the poster is ${brightness(poster)}, not the white picture")
        val frames = VideoThumbnailService.extractThumbnailStrip(clip(name), IosVideoProber.getDurationMs(clip(name)), 4, 96).toList()
        assertEquals((0 until 4).toList(), frames.map { it.index }.sorted(), "strip slots")
        for (frame in frames) assertTrue(brightness(frame.jpegBytes) > 200, "strip frame ${frame.index} is ${brightness(frame.jpegBytes)}")
    }

    @OptIn(BetaInteropApi::class)
    private fun brightness(jpeg: ByteArray): Int = jpeg.usePinned {
        val image = checkNotNull(UIImage.imageWithData(NSData.create(bytes = it.addressOf(0), length = jpeg.size.toULong()))?.CGImage)
        val data = CGDataProviderCopyData(CGImageGetDataProvider(image))
        try {
            val bytes = checkNotNull(CFDataGetBytePtr(data))
            val length = CFDataGetLength(data).toInt()
            (0 until length).sumOf { i -> bytes[i].toInt() } / length
        } finally {
            CFRelease(data)
        }
    }

    // What AVFoundation says when a poster comes back empty: the decoder's asynchronous call drops its error.
    private fun posterError(name: String, fullSize: Boolean): String = memScoped {
        val generator = sdrGenerator(clip(name))?.first ?: return "no video track"
        val maxPx = if (fullSize) VideoThumbnailQuality.FULL_POSTER_LONG_EDGE_PX.toDouble() else 640.0
        generator.setMaximumSize(CGSizeMake(maxPx, maxPx))
        val error = alloc<ObjCObjectVar<NSError?>>()
        val image = generator.copyCGImageAtTime(CMTimeMake(100, 1000), null, error.ptr)
        if (image != null) {
            CGImageRelease(image)
            "the synchronous call gave an image"
        } else {
            "${error.value?.domain} ${error.value?.code}: ${error.value?.localizedDescription}; ${error.value?.userInfo}"
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
            assertEquals(59.9139, location.latitude, 1e-4)
            assertEquals(10.7522, location.longitude, 1e-4)
            assertEquals(CAPTURED_AT, captureTime(out))
        }
    }

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
