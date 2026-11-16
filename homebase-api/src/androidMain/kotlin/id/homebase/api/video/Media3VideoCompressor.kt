package id.homebase.api.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
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
import java.io.File
import java.io.RandomAccessFile
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
        val input = File(inputPath)
        val targets = quality.targets()
        val trim = if (trimStartMs != null && trimEndMs != null) trimStartMs to trimEndMs else null
        val (source, output) = withContext(Dispatchers.IO) {
            if (!input.exists()) throw VideoCompressionFailedException(inputPath, "input file not found")
            val cacheDir = ActivityProvider.requireApplicationContext().cacheDir.absolutePath
            probeSource(input, keepMetadata) to File(AppCacheDirs.scratchDir(cacheDir, AppCacheDirs.MEDIA_WORK), "compressed_${UUID.randomUUID()}.mp4")
        }

        val mediaItem = MediaItem.Builder().setUri(input.toURI().toString()).apply {
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
        } catch (e: ExportException) {
            output.delete()
            throw VideoCompressionFailedException(inputPath, "Media3 export failed: ${e.errorCodeName}", e)
        }
        Logger.i(tag = TAG) {
            "compress: ${System.currentTimeMillis() - started}ms in=${input.length()}B ${source.width}x${source.height} " +
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
        val context = ActivityProvider.requireApplicationContext()
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(targets.videoBitrateBps).build())
            .apply {
                // With no audio settings asked for, Transformer copies AAC as it is.
                if (!source.aacAudio) setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(targets.audioBitrateBps).build())
            }
            .build()
        val muxer = InAppMp4Muxer.Factory { entries -> withMetadata(entries, source.takeIf { keepMetadata }) }
        val transformer = Transformer.Builder(context)
            .setLooper(looper)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            .setMuxerFactory(muxer)
            .build()
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
                } finally {
                    progress?.cancel()
                }
            }
        } catch (e: CancellationException) {
            // Returns once the codecs are released, so the caller's lock outlasts the work.
            withContext(NonCancellable) { transformer.cancel() }
            throw e
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

    private class Source(val width: Int, val height: Int, val aacAudio: Boolean, val location: VideoLocation?)

    // Headers only: Transformer's export is the one pass over the media.
    private fun probeSource(file: File, keepMetadata: Boolean): Source {
        val extractor = MediaExtractor()
        val formats = try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        } catch (e: Exception) {
            emptyList()
        } finally {
            extractor.release()
        }
        val video = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        return Source(
            width = video?.getInteger(MediaFormat.KEY_WIDTH) ?: 0,
            height = video?.getInteger(MediaFormat.KEY_HEIGHT) ?: 0,
            aacAudio = formats.any { it.getString(MediaFormat.KEY_MIME) == MimeTypes.AUDIO_AAC },
            location = if (keepMetadata) moovLocation(file) else null,
        )
    }

    // Seeks past the media, so only the box headers and the moov are read.
    private fun moovLocation(file: File): VideoLocation? = try {
        RandomAccessFile(file, "r").use { raf ->
            val reader = VideoLocationReader()
            val chunk = ByteArray(64 * 1024)
            while (!reader.finished) {
                raf.seek(raf.filePointer + reader.takeSkip())
                val n = raf.read(chunk)
                if (n <= 0) break
                reader.feed(if (n == chunk.size) chunk else chunk.copyOf(n))
            }
            reader.location
        }
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "Couldn't read ${file.name}'s location" }
        null
    }
}
