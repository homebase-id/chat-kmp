package id.homebase.api.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Looper
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
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope

/**
 * Compresses on the phone's hardware with Media3 Transformer: MediaCodec decode, GL scale, MediaCodec
 * encode. Output matches [FfmpegCompressPlanner]'s: H.264 + AAC at [VideoQuality.targets], short edge
 * scaled down only, 8-bit SDR (HDR is tone-mapped), and the source's metadata kept or dropped.
 */
@OptIn(UnstableApi::class)
internal object Media3VideoCompressor {
    private const val TAG = "Media3VideoCompressor"
    private const val PROGRESS_POLL_MS = 250L
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024

    suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
        keepMetadata: Boolean,
    ): String {
        val context = ActivityProvider.requireApplicationContext()
        val input = File(inputPath)
        if (!input.exists()) throw VideoCompressionFailedException(inputPath, "input file not found")
        val trim = if (trimStartMs != null && trimEndMs != null) trimStartMs to trimEndMs else null
        val targets = quality.targets()
        val source = probeSource(inputPath)
        val output = File(AppCacheDirs.scratchDir(context.cacheDir.absolutePath, AppCacheDirs.MEDIA_WORK), "compressed_${UUID.randomUUID()}.mp4")

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
        // Short side only, so portrait and landscape scale alike; never up.
        val scale = if (minOf(source.width, source.height) > targets.shortEdgePx) {
            listOf(Presentation.createForShortSide(targets.shortEdgePx).copyWithUnsetSideRoundedTo(2))
        } else {
            emptyList()
        }
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(
                EditedMediaItem.Builder(mediaItem).setEffects(Effects(emptyList(), scale)).build(),
            ).build(),
        ).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()

        val durationMs = trim?.let { it.second - it.first } ?: 0L
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
                "out=${output.length()}B trimMs=$durationMs quality=$quality"
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
    ) = withContext(Dispatchers.Main) {
        val context = ActivityProvider.requireApplicationContext()
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(targets.videoBitrateBps).build())
            .apply {
                // Android's AAC codecs are software and run about 5x real time, slower than the
                // hardware video path; AAC audio is copied instead, and only other audio re-encoded.
                if (!source.aacAudio) setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(targets.audioBitrateBps).build())
            }
            .build()
        val muxer = InAppMp4Muxer.Factory { entries -> withMetadata(entries, source.takeIf { keepMetadata }) }
        val transformer = Transformer.Builder(context)
            .setLooper(Looper.getMainLooper())
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
                            "video=${exportResult.videoEncoderName} audio=${exportResult.audioEncoderName}"
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

    private fun probeSource(path: String): Source {
        val retriever = MediaMetadataRetriever()
        val (width, height) = try {
            retriever.setDataSource(path)
            fun read(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            read(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) to read(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        } catch (e: Exception) {
            0 to 0
        } finally {
            retriever.release()
        }
        return Source(width, height, hasAacAudio(path), moovLocation(File(path)))
    }

    private fun hasAacAudio(path: String): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            (0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MimeTypes.AUDIO_AAC }
        } catch (e: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    // Reads only the moov box, so the location costs no pass over the media.
    private fun moovLocation(file: File): VideoLocation? = try {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteBuffer.allocate(16)
            var at = 0L
            while (at + 8 <= raf.length()) {
                raf.seek(at)
                raf.readFully(header.array(), 0, 8)
                var size = header.getInt(0).toLong() and 0xFFFFFFFFL
                val type = String(header.array(), 4, 4, Charsets.ISO_8859_1)
                if (size == 1L) {
                    raf.readFully(header.array(), 8, 8)
                    size = header.getLong(8)
                } else if (size == 0L) {
                    size = raf.length() - at
                }
                if (size < 8) return@use null
                if (type == "moov") {
                    if (size > MAX_MOOV_BYTES) return@use null
                    val moov = ByteArray(size.toInt())
                    raf.seek(at)
                    raf.readFully(moov)
                    return@use VideoLocationReader().apply { feed(moov) }.location
                }
                at += size
            }
            null
        }
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "Couldn't read ${file.name}'s location" }
        null
    }
}
