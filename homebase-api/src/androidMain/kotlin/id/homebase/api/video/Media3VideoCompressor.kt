package id.homebase.api.video

import id.homebase.api.media.MediaLocation
import id.homebase.api.media.MediaMetadataReader
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4OrientationData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import co.touchlab.kermit.Logger
import id.homebase.api.ActivityProvider
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.AndroidFileOperationsProvider
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Compresses on the phone's hardware with Media3 Transformer: MediaCodec decode, GL scale, MediaCodec
 * encode. The video follows the same rules as ffmpeg's: H.264 at [VideoQuality.targets], short edge
 * scaled down only ([computeOutputDims]), 8-bit SDR (HDR is tone-mapped), and the source's metadata kept or dropped.
 * AAC audio is copied, not re-encoded: see THUMBNAILS.md, "Android: Media3, and why AAC audio is copied".
 */
@OptIn(UnstableApi::class)
internal object Media3VideoCompressor {
    private const val TAG = "Media3VideoCompressor"
    private const val PROGRESS_POLL_MS = 250L
    internal val fileOps by lazy { AndroidFileOperationsProvider(ActivityProvider.requireApplicationContext()) }

    // Transformer's calls, callbacks and blocking cancel stay off the main thread.
    private val looper by lazy { HandlerThread("Media3Transformer").apply { start() }.looper }
    private val transformerThread by lazy { Handler(looper).asCoroutineDispatcher() }

    suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
        keepMetadata: Boolean,
    ): String {
        val uri = if (fileOps.isContentUri(inputPath)) Uri.parse(inputPath) else File(inputPath).toUri()
        val targets = quality.targets()
        val trim = if (trimStartMs != null && trimEndMs != null) trimStartMs to trimEndMs else null
        val (source, output) = withContext(Dispatchers.IO) {
            if (!fileOps.sourceExists(inputPath)) throw VideoCompressionFailedException(inputPath, "input file not found")
            val cacheDir = fileOps.context.cacheDir.absolutePath
            probeSource(inputPath, uri, keepMetadata) to File(AppCacheDirs.scratchDir(cacheDir, AppCacheDirs.MEDIA_WORK), "compressed_${UUID.randomUUID()}.mp4")
        }

        val mediaItem = MediaItem.Builder().setUri(uri).apply {
            if (trim != null) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(trim.first)
                        .setEndPositionMs(trim.second)
                        .build(),
                )
            }
        }.build()
        val scale = if (computeOutputDims(source.width, source.height, targets.shortEdgePx) != null) {
            listOf(Presentation.createForShortSide(targets.shortEdgePx).copyWithUnsetSideRoundedTo(2))
        } else {
            emptyList()
        }
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(
                EditedMediaItem.Builder(mediaItem).setEffects(Effects(emptyList(), scale)).build(),
            ).build(),
        ).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()

        val started = System.currentTimeMillis()
        try {
            export(composition, output, targets, source, keepMetadata, onProgress)
        } catch (e: CancellationException) {
            output.delete()
            throw e
        } catch (e: Exception) {
            output.delete()
            val reason = (e as? ExportException)?.errorCodeName ?: e.toString()
            throw VideoCompressionFailedException(inputPath, "Media3 export failed: $reason", e)
        }
        Logger.i(tag = TAG) {
            "compress: ${System.currentTimeMillis() - started}ms ${source.width}x${source.height} " +
                "out=${output.length()}B trimMs=${trim?.let { it.second - it.first } ?: 0} quality=$quality"
        }
        return output.absolutePath
    }

    private suspend fun export(
        composition: Composition,
        output: File,
        targets: QualityTargets,
        source: Source,
        keepMetadata: Boolean,
        onProgress: VideoProgressListener?,
    ) = withContext(transformerThread) {
        val context = fileOps.context
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(targets.hardwareVideoBitrate(source.width, source.height, source.videoBitrateBps)).build())
            .apply {
                // With no audio settings asked for, Transformer copies AAC as it is.
                if (!source.copyableAudio) setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(targets.audioBitrateBps).build())
            }
            .build()
        // Streamable output reserves about 400 kB for the index up front, left as a free box. Nothing here streams an
        // MP4 progressively (payloads are decrypted whole; the segmenter finds the index anywhere), so it goes at the end.
        val muxer = InAppMp4Muxer.Factory { entries -> withMetadata(entries, source.takeIf { keepMetadata }) }
            .setAttemptStreamableOutputEnabled(false)
        val transformer = Transformer.Builder(context)
            .setLooper(looper)
            // Portrait frames rather than a rotation flag: an HLS stream can't carry one.
            .setPortraitEncodingEnabled(true)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            .setMuxerFactory(muxer)
            .build()
        var completed = false
        try {
            coroutineScope {
                val progress = onProgress?.let { report ->
                    launch {
                        val holder = ProgressHolder()
                        while (isActive) {
                            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                report(holder.progress / 100f)
                            }
                            delay(PROGRESS_POLL_MS)
                        }
                    }
                }
                try {
                    awaitExport(transformer, composition, output)
                    completed = true
                } finally {
                    progress?.cancel()
                }
            }
        } finally {
            // Returns once the codecs are released, so the caller's lock outlasts the work.
            if (!completed) withContext(NonCancellable) { transformer.cancel() }
        }
    }

    private suspend fun awaitExport(transformer: Transformer, composition: Composition, output: File) =
        suspendCancellableCoroutine { cont ->
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Logger.i(tag = TAG) {
                        "export: ${exportResult.videoFrameCount} frames ${exportResult.width}x${exportResult.height} " +
                            "video=${exportResult.videoEncoderName} audio=${exportResult.audioEncoderName ?: "not re-encoded"}"
                    }
                    cont.resume(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    cont.resumeWithException(exportException)
                }
            })
            transformer.start(composition, output.absolutePath)
        }

    // Transformer passes on rotation and capture time but not the location: a kept video gets it
    // back, a stripped one keeps only its rotation.
    private fun withMetadata(entries: MutableSet<Metadata.Entry>, kept: Source?) {
        if (kept == null) {
            entries.retainAll { it is Mp4OrientationData }
        } else {
            kept.location?.let { entries += Mp4LocationData(it.latitude.toFloat(), it.longitude.toFloat()) }
        }
    }

    private class Source(val width: Int, val height: Int, val videoBitrateBps: Int?, val copyableAudio: Boolean, val location: MediaLocation?)

    // Headers only: Transformer's export is the one pass over the media.
    private suspend fun probeSource(path: String, uri: Uri, keepMetadata: Boolean): Source {
        val extractor = MediaExtractor()
        val formats = try {
            extractor.setDataSource(fileOps.context, uri, null)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        } catch (e: Exception) {
            emptyList()
        } finally {
            extractor.release()
        }
        val video = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val audio = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        return Source(
            width = video?.int(MediaFormat.KEY_WIDTH) ?: 0,
            height = video?.int(MediaFormat.KEY_HEIGHT) ?: 0,
            videoBitrateBps = video?.int(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 },
            // Plain stereo AAC-LC plays everywhere; HE-AAC, xHE-AAC and surround are re-encoded.
            copyableAudio = audio.size == 1 && audio[0].let {
                it.getString(MediaFormat.KEY_MIME) == MimeTypes.AUDIO_AAC &&
                    it.int(MediaFormat.KEY_AAC_PROFILE) == MediaCodecInfo.CodecProfileLevel.AACObjectLC &&
                    (it.int(MediaFormat.KEY_CHANNEL_COUNT) ?: 0) in 1..2
            },
            location = if (keepMetadata) MediaMetadataReader(fileOps).videoLocation(path) else null,
        )
    }

    private fun MediaFormat.int(key: String): Int? = if (containsKey(key)) getInteger(key) else null
}
