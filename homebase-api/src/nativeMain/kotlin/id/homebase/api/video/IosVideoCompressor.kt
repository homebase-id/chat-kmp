package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.IOSFileOperationsProvider
import id.homebase.api.file.scratchDir
import id.homebase.api.image.ns
import id.homebase.api.image.writeLe32
import id.homebase.api.media.MediaMetadataReader
import id.homebase.api.video.hls.HlsSegmenter
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderOutput
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetReaderVideoCompositionOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusFailed
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVChannelLayoutKey
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataIdentifierQuickTimeMetadataLocationISO6709
import platform.AVFoundation.AVMutableMetadataItem
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoColorPrimariesKey
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoColorPropertiesKey
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoExpectedSourceFrameRateKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoMaxKeyFrameIntervalDurationKey
import platform.AVFoundation.AVVideoProfileLevelH264HighAutoLevel
import platform.AVFoundation.AVVideoProfileLevelKey
import platform.AVFoundation.AVVideoTransferFunctionKey
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.AVVideoYCbCrMatrixKey
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.AVFoundation.creationDate
import platform.AVFoundation.duration
import platform.AVFoundation.estimatedDataRate
import platform.AVFoundation.formatDescriptions
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreAudioTypes.kAudioChannelLayoutTag_Mono
import platform.CoreAudioTypes.kAudioChannelLayoutTag_Stereo
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMAudioFormatDescriptionGetRichestDecodableFormat
import platform.CoreMedia.CMAudioFormatDescriptionGetStreamBasicDescription
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.darwin.dispatch_queue_create

internal actual fun platformVideoCompressor(): VideoCompressor = IosVideoCompressor

/** AVFoundation's reader and writer, not an export session, whose presets can't set a bitrate; our segmenter cuts HLS. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalUuidApi::class, ExperimentalAtomicApi::class)
internal object IosVideoCompressor : VideoCompressor {
    private const val KEYFRAME_SECONDS = 2
    internal val fileOps by lazy { IOSFileOperationsProvider() }

    override suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
        keepMetadata: Boolean,
    ): String {
        val input = filePath(inputPath)
        val output = withContext(Dispatchers.IO) {
            if (!NSFileManager.defaultManager.fileExistsAtPath(input)) throw VideoCompressionFailedException(inputPath, "input file not found")
            "${fileOps.scratchDir(AppCacheDirs.MEDIA_WORK)}/compressed_${Uuid.random()}.mp4"
        }
        val trim = if (trimStartMs != null && trimEndMs != null) trimStartMs to trimEndMs else null
        val started = TimeSource.Monotonic.markNow()
        try {
            Export(input, output, quality.targets(), trim, keepMetadata).run(onProgress)
        } catch (e: Exception) {
            fileOps.deleteTempFile(output)
            if (e is CancellationException || e is VideoCompressionFailedException) throw e
            throw VideoCompressionFailedException(inputPath, "AVFoundation export failed: ${e.message}", e)
        }
        Logger.i(tag = TAG) { "compress: ${started.elapsedNow().inWholeMilliseconds}ms out=${fileOps.getFileSize(output)}B quality=$quality" }
        onProgress?.invoke(1f)
        return output
    }

    override suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? = HlsSegmenter.segmentForUpload(fileOps, filePath(inputPath), keyHeader, onProgress)

    private class Export(
        private val input: String,
        private val output: String,
        private val targets: QualityTargets,
        private val trim: Pair<Long, Long>?,
        private val keepMetadata: Boolean,
    ) {
        private val asset = AVURLAsset.URLAssetWithURL(fileUrl(input), null)

        suspend fun run(onProgress: VideoProgressListener?) = withContext(Dispatchers.IO) {
            val video = asset.firstVideoTrack() ?: fail("no video track")
            val reader = errorOut { AVAssetReader(asset, it) } ?: fail("can't read")
            val writer = errorOut { AVAssetWriter(fileUrl(output), AVFileTypeMPEG4, it) } ?: fail("can't write")
            writer.shouldOptimizeForNetworkUse = false
            val start = CMTimeMake(trim?.first ?: 0L, 1000)
            trim?.let { (from, to) -> reader.timeRange = CMTimeRangeMake(start, CMTimeMake(to - from, 1000)) }
            val startSeconds = CMTimeGetSeconds(start)
            val lengthSeconds = trim?.let { (from, to) -> (to - from) / 1000.0 } ?: CMTimeGetSeconds(asset.duration)
            val onTime: (Double) -> Unit = { seconds ->
                if (lengthSeconds > 0) onProgress?.invoke(((seconds - startSeconds) / lengthSeconds).toFloat().coerceIn(0f, 1f))
            }

            val pumps = mutableListOf(videoPump(writer, video, onTime))
            (asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack)?.let { pumps += audioPump(writer, it) }
            // AVFoundation raises an Objective-C exception, which ends the app, where these say no.
            for (pump in pumps) {
                if (!reader.canAddOutput(pump.output) || !writer.canAddInput(pump.input)) fail("AVFoundation refused a track")
                reader.addOutput(pump.output)
                writer.addInput(pump.input)
            }
            // In an MP4 these become 3GPP's loci and date boxes.
            if (keepMetadata) {
                writer.metadata = listOfNotNull(
                    // AVFoundation can't see a location ffmpeg or Android wrote, so the shared reader finds it.
                    MediaMetadataReader(fileOps).videoLocation(input)?.let { place ->
                        AVMutableMetadataItem().apply {
                            setIdentifier(AVMetadataIdentifierQuickTimeMetadataLocationISO6709)
                            setValue(NSString.create(string = place.toIso6709()))
                        }
                    },
                    asset.creationDate,
                )
            }

            if (!writer.startWriting()) fail("writer didn't start", writer.error)
            if (!reader.startReading()) fail("reader didn't start", reader.error)
            writer.startSessionAtSourceTime(start)

            val drained = CompletableDeferred<Unit>()
            val refused = CompletableDeferred<Unit>()
            val running = atomic(pumps.size)
            val stop = AtomicBoolean(false)
            pumps.forEach { pump ->
                pump.start(stop) { wasRefused ->
                    if (wasRefused) refused.complete(Unit)
                    if (running.decrementAndGet() == 0) drained.complete(Unit)
                }
            }
            // A sample appended to a writer that's failed or cancelled raises, so the pumps stop before the writer goes.
            suspend fun stopPumps() {
                stop.store(true)
                reader.cancelReading()
                if (withTimeoutOrNull(5.seconds) { drained.await() } == null) Logger.w(tag = TAG) { "pumps didn't stop" }
            }
            try {
                select<Unit> {
                    drained.onAwait {}
                    refused.onAwait {}
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    stopPumps()
                    writer.cancelWriting()
                }
                throw e
            }
            if (refused.isCompleted || writer.status == AVAssetWriterStatusFailed) {
                stopPumps()
                fail("writing failed", writer.error)
            }
            if (reader.status == AVAssetReaderStatusFailed) {
                writer.cancelWriting()
                fail("reading failed", reader.error)
            }
            // Not cancellable: the file is the writer's until it says it's done.
            withContext(NonCancellable) { suspendCancellableCoroutine { cont -> writer.finishWritingWithCompletionHandler { cont.resume(Unit) } } }
            if (writer.status != AVAssetWriterStatusCompleted) fail("writing failed", writer.error)
        }

        // The composition turns the frames upright (an HLS stream has no rotation flag), scales them, and maps HDR to SDR.
        private fun videoPump(writer: AVAssetWriter, track: AVAssetTrack, onTime: (Double) -> Unit): Pump {
            val (srcW, srcH) = track.uprightSize()
            val (width, height) = computeOutputDims(srcW, srcH, targets.shortEdgePx) ?: (srcW.toEven() to srcH.toEven())
            val fps = track.compositionFps()
            val composition = uprightSdrComposition(asset, track, width, height, fps)
            val output = AVAssetReaderVideoCompositionOutput(listOf(track), mapOf<Any?, Any?>(kCVPixelBufferPixelFormatTypeKey.ns to kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)).apply {
                videoComposition = composition
                alwaysCopiesSampleData = false
            }
            val bitrate = targets.hardwareVideoBitrate(srcW, srcH, track.estimatedDataRate.toInt().takeIf { it > 0 })
            val settings = mapOf<Any?, Any?>(
                AVVideoCodecKey to AVVideoCodecTypeH264,
                AVVideoWidthKey to width,
                AVVideoHeightKey to height,
                AVVideoCompressionPropertiesKey to mapOf<Any?, Any?>(
                    AVVideoAverageBitRateKey to bitrate,
                    AVVideoProfileLevelKey to AVVideoProfileLevelH264HighAutoLevel,
                    AVVideoMaxKeyFrameIntervalDurationKey to KEYFRAME_SECONDS,
                    AVVideoExpectedSourceFrameRateKey to fps,
                ),
                AVVideoColorPropertiesKey to mapOf<Any?, Any?>(
                    AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_709_2,
                    AVVideoTransferFunctionKey to AVVideoTransferFunction_ITU_R_709_2,
                    AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_709_2,
                ),
            )
            val input = writerInput(writer, AVMediaTypeVideo, settings)
            Logger.i(tag = TAG) { "video: ${srcW}x$srcH -> ${width}x$height @$fps fps, ${bitrate / 1000} kbps" }
            return Pump(output, input, onTime)
        }

        // Plain AAC-LC with up to two channels is copied, as on Android; anything else is re-encoded to AAC-LC.
        private fun audioPump(writer: AVAssetWriter, track: AVAssetTrack): Pump {
            val format = track.retainedFormat()
            try {
                val basic = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointed
                val richest = CMAudioFormatDescriptionGetRichestDecodableFormat(format)?.pointed?.mASBD
                val channels = basic?.mChannelsPerFrame?.toInt() ?: 2
                if (basic?.mFormatID == kAudioFormatMPEG4AAC && richest?.mFormatID == kAudioFormatMPEG4AAC && channels in 1..2) {
                    Logger.i(tag = TAG) { "audio: copied" }
                    return Pump(
                        AVAssetReaderTrackOutput(track, null),
                        AVAssetWriterInput(AVMediaTypeAudio, null, format).apply { expectsMediaDataInRealTime = false },
                    )
                }
                return reencodedAudio(writer, track, channels.coerceIn(1, 2), basic?.mSampleRate ?: 48_000.0)
            } finally {
                format?.let { CFRelease(it) }
            }
        }

        // AAC at the quality's bitrate needs 44.1 or 48 kHz: an 8 kHz voice track can't carry 128 kbps.
        private fun reencodedAudio(writer: AVAssetWriter, track: AVAssetTrack, channels: Int, sourceRate: Double): Pump {
            val sampleRate = if (sourceRate > 44_100.0) 48_000.0 else 44_100.0
            Logger.i(tag = TAG) { "audio: re-encoded to AAC, $channels ch, ${sourceRate.toInt()} -> ${sampleRate.toInt()} Hz" }
            // An AudioChannelLayout with only its tag set: 3 UInt32 fields and one empty channel description.
            val tag = if (channels == 1) kAudioChannelLayoutTag_Mono else kAudioChannelLayoutTag_Stereo
            val layout = ByteArray(32).also { writeLe32(it, 0, tag.toInt()) }.toNSData()
            val output = AVAssetReaderTrackOutput(track, mapOf<Any?, Any?>(
                AVFormatIDKey to kAudioFormatLinearPCM,
                AVNumberOfChannelsKey to channels,
                AVSampleRateKey to sampleRate,
                AVChannelLayoutKey to layout,
            ))
            val input = writerInput(writer, AVMediaTypeAudio, mapOf<Any?, Any?>(
                AVFormatIDKey to kAudioFormatMPEG4AAC,
                AVNumberOfChannelsKey to channels,
                AVSampleRateKey to sampleRate,
                AVEncoderBitRateKey to targets.audioBitrateBps,
                AVChannelLayoutKey to layout,
            ))
            return Pump(output, input)
        }

        private fun writerInput(writer: AVAssetWriter, mediaType: String?, settings: Map<Any?, Any?>): AVAssetWriterInput {
            if (!writer.canApplyOutputSettings(settings, mediaType)) fail("AVFoundation refused the $mediaType settings $settings")
            return AVAssetWriterInput(mediaType, settings).apply { expectsMediaDataInRealTime = false }
        }

        private fun fail(reason: String, error: NSError? = null): Nothing =
            throw VideoCompressionFailedException(input, error?.localizedDescription?.let { "$reason: $it" } ?: reason)
    }

    /** Copies [output]'s samples into [input] on its own queue, as the writer asks for them. */
    private class Pump(val output: AVAssetReaderOutput, val input: AVAssetWriterInput, private val onTime: ((Double) -> Unit)? = null) {
        fun start(stop: AtomicBoolean, onDone: (refused: Boolean) -> Unit) {
            val done = atomic(false)
            fun finish(refused: Boolean = false) {
                if (done.compareAndSet(false, true)) {
                    if (!refused && !stop.load()) input.markAsFinished()
                    onDone(refused)
                }
            }
            input.requestMediaDataWhenReadyOnQueue(dispatch_queue_create("id.homebase.video.pump", null)) {
                while (!done.value && input.isReadyForMoreMediaData()) {
                    if (stop.load()) return@requestMediaDataWhenReadyOnQueue finish()
                    val sample = output.copyNextSampleBuffer() ?: return@requestMediaDataWhenReadyOnQueue finish()
                    try {
                        onTime?.invoke(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample)))
                        if (!input.appendSampleBuffer(sample)) return@requestMediaDataWhenReadyOnQueue finish(refused = true)
                    } finally {
                        CFRelease(sample)
                    }
                }
            }
        }
    }
}

private const val TAG = "IosVideoCompressor"
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun <T> errorOut(make: (CPointer<ObjCObjectVar<NSError?>>) -> T?): T? = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    make(error.ptr).also { if (it == null) Logger.w(tag = TAG) { "AVFoundation: ${error.value?.localizedDescription}" } }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }
