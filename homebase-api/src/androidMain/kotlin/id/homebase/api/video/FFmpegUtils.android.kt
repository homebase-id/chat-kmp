package id.homebase.api.video

import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import id.homebase.api.ActivityProvider
import id.homebase.api.client.KeyHeader
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

actual object FFmpegUtils {
    private const val TAG = "FFmpegUtils"

    @Volatile private var cachedFfmpegVersion: String? = null
    @Volatile private var ffmpegVersionProbed: Boolean = false

    actual suspend fun getFfmpegVersion(): String? = withContext(Dispatchers.IO) {
        if (ffmpegVersionProbed) return@withContext cachedFfmpegVersion
        // FFmpegKitConfig.getFFmpegVersion() calls FFmpeg's av_version_info()
        // directly via JNI (no `ffmpeg -version` subprocess). The previous
        // approach — FFmpegKit.execute("-version") + parseFfmpegVersionBanner —
        // doesn't work because ffmpeg's show_version writes to stdout via
        // printf AND swaps the log callback away from arthenica's capture
        // hook before printing, so session.allLogsAsString comes back empty.
        val v = try {
            FFmpegKitConfig.getFFmpegVersion()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "getFfmpegVersion failed", e)
            null
        }
        cachedFfmpegVersion = v
        ffmpegVersionProbed = true
        v
    }

    actual suspend fun getDurationMs(inputPath: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            // setDataSource(String) requires a filesystem path. FileKit returns
            // content:// URIs for gallery picks — use the (Context, Uri) overload.
            if (inputPath.startsWith("content://") || inputPath.startsWith("content:")) {
                val context = ActivityProvider.requireApplicationContext()
                retriever.setDataSource(context, inputPath.toUri())
            } else {
                retriever.setDataSource(inputPath)
            }
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    actual fun getUniqueId(filePath: String): String {
        // TODO: potential BUG — for content:// URIs, `File(filePath).length()`
        // returns 0 and `File(filePath).name` is the encoded URI tail, so every
        // gallery pick collapses toward the same hash bucket. Callers that
        // cache by this id (grabThumbnail, etc.) can return another video's
        // cached output. Fix by resolving the URI to size via
        // ContentResolver.openAssetFileDescriptor(...).length when filePath
        // starts with "content://".
        val file = File(filePath)
        return UUID.nameUUIDFromBytes("${file.name}_${file.length()}".toByteArray()).toString()
    }

    // No `grabThumbnail` actual: Android's thumbnail decoder is *currently*
    // MediaMetadataRetriever-backed (platformFfmpegDecoder() == null), so nothing on this
    // platform calls the ffmpeg poster helper today, and grabThumbnail is no longer in the
    // FFmpegUtils expect. Poster frames go through VideoThumbnailService.extractPosterFrame.
    //
    // An Android ffmpeg fallback is still possible: add a VideoDecoder that drives FFmpegKit
    // (directly, as the compressVideo path below already does, or via a re-added Android-internal
    // grabThumbnail mirroring the JVM/native helpers) and return it from platformFfmpegDecoder().
    // If you do, that decoder MUST serialize its ffmpeg_execute against compress/segment: Android's
    // FFmpegKit is the same non-reentrant engine that SIGSEGV'd on iOS when two sessions overlapped
    // (PR #644). Today the MediaMetadataRetriever path is the only reason Android can't hit that.

    actual suspend fun getRotationFromFile(filePath: String): Int =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                if (filePath.startsWith("content://") || filePath.startsWith("content:")) {
                    val context = ActivityProvider.requireApplicationContext()
                    retriever.setDataSource(context, filePath.toUri())
                } else {
                    retriever.setDataSource(filePath)
                }
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                    ?: 0
                if (rotation in -360..360) rotation else 0
            } catch (e: Exception) {
                Log.w(TAG, "getRotationFromFile failed", e)
                0
            } finally {
                retriever.runCatching { release() }
            }
        }

    /**
     * Compress + optionally trim a video file via ffmpeg-kit. Returns the
     * absolute path of the compressed MP4, or null if the input is already
     * within the target quality envelope (caller falls back to the original
     * file) or if ffmpeg failed.
     *
     * Pure probe-and-invoke wrapper around [FfmpegCompressPlanner] — the
     * planner (commonMain) owns the quality→target mapping, already-optimal
     * predicate, and ffmpeg arg list assembly. This actual contributes:
     * MediaExtractor-based probe, FFmpegKit invocation, timing log, EXIF
     * strip on the short-circuit path.
     */
    actual suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
    ): String = withContext(Dispatchers.IO) {
        val context = ActivityProvider.requireApplicationContext()
        val inFile = File(inputPath)
        if (!inFile.exists()) {
            Log.e(TAG, "File not found: $inputPath")
            throw VideoCompressionFailedException(inputPath, "input file not found")
        }

        if ((trimStartMs == null) != (trimEndMs == null)) {
            Log.w(TAG, "Partial trim ignored (got start=$trimStartMs end=$trimEndMs); pass both or neither")
        }
        val effectiveTrimStart = if (trimStartMs != null && trimEndMs != null) trimStartMs else null
        val effectiveTrimEnd = if (trimStartMs != null && trimEndMs != null) trimEndMs else null

        val outFile = File(context.cacheDir, "compressed_${inFile.name}")
        val inputDurationMs = getDurationMs(inputPath)
        val inputBytes = inFile.length()
        val probe = probeVideoTrack(inputPath)
        // MediaExtractor reports raw container dims; rotation lives in a
        // separate track-header field. Planner needs both so it can swap
        // before computing the scale target (else portrait camera captures
        // get a landscape scale and the decoded frames get squished).
        val rotation = getRotationFromFile(inputPath)
        val trimDurationMs = if (effectiveTrimEnd != null && effectiveTrimStart != null) {
            effectiveTrimEnd - effectiveTrimStart
        } else {
            inputDurationMs
        }
        val t0 = System.currentTimeMillis()

        // Hand probed input + caller params to the commonMain planner.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = inputPath,
            outputPath = outFile.absolutePath,
            quality = quality,
            trimStartMs = effectiveTrimStart,
            trimEndMs = effectiveTrimEnd,
            probedWidthPx = probe.widthPx,
            probedHeightPx = probe.heightPx,
            probedCodecMime = if (probe.videoTrackCount == 1 && probe.audioTrackCount <= 1) probe.videoMime else null,
            inputDurationMs = inputDurationMs,
            inputBytes = inputBytes,
            rotationDegrees = rotation,
            // Default encoder = libx264. Android doesn't expose a hardware
            // libavcodec wrapper (h264_mediacodec exists but is unreliable in
            // FFmpegKit builds); stick with libx264 for predictable behaviour.
            probedBitDepth = probe.bitDepth,
            probedIsHdr = probe.isHdr,
        )

        val args = plan.args.toTypedArray()
        Log.d(TAG, "compressVideo args: ${args.joinToString(" ")}")

        // executeWithArgumentsAsync, not Sync. FFmpegKit's sync API has known
        // issues with re-entry: running ffmpeg's main() twice in the same
        // process can crash because some internal global state isn't reset
        // properly. The async variant runs each invocation on a fresh worker
        // thread, avoiding the issue. Verified empirically on the API 36
        // emulator running compressVideo 4× sequentially.
        val session = try {
            executeFfmpegAsync(args) { stats ->
                onProgress?.invoke(progressFraction(stats.time.toLong(), trimDurationMs))
            }
        } catch (e: Throwable) {
            val elapsedMs = System.currentTimeMillis() - t0
            Log.e(TAG, "compressVideo crashed after ${elapsedMs}ms", e)
            outFile.delete()
            throw VideoCompressionFailedException(inputPath, "ffmpeg crashed after ${elapsedMs}ms", e)
        }

        val elapsedMs = System.currentTimeMillis() - t0
        if (!ReturnCode.isSuccess(session.returnCode)) {
            Log.e(
                TAG,
                "compressVideo FAILED after ${elapsedMs}ms (rc=${session.returnCode}, " +
                    "in=$inputPath, inputBytes=$inputBytes, inputDurationMs=$inputDurationMs, " +
                    "trimDurationMs=$trimDurationMs, quality=$quality): ${session.failStackTrace}"
            )
            outFile.delete()
            throw VideoCompressionFailedException(
                inputPath, "ffmpeg returned rc=${session.returnCode}"
            )
        }

        val outBytes = outFile.length()
        val realtimeRatio = if (trimDurationMs > 0) {
            "%.2f".format(elapsedMs.toFloat() / trimDurationMs)
        } else {
            "n/a"
        }
        Log.i(
            TAG,
            "compressVideo: ${elapsedMs}ms (Transcoded, ${realtimeRatio}× realtime) " +
                "in=${inputBytes}B/${inputDurationMs}ms out=${outBytes}B " +
                "outDims=${plan.outputDims?.first}x${plan.outputDims?.second} " +
                "trimDurationMs=$trimDurationMs quality=$quality",
        )
        outFile.absolutePath
    }

    /**
     * Probe of the first video track via MediaExtractor: codec MIME, width,
     * height, plus track counts so the caller can decide whether the input is
     * "single video + ≤1 audio" (a precondition for the already-optimal
     * short-circuit). Returns zeros / null on any probe failure.
     */
    private data class VideoTrackProbe(
        val videoTrackCount: Int,
        val audioTrackCount: Int,
        val videoMime: String?,
        val widthPx: Int,
        val heightPx: Int,
        /** Null when MediaExtractor didn't expose the profile/color keys — see [readBitDepth]. */
        val bitDepth: Int?,
        val isHdr: Boolean?,
    )

    actual suspend fun probeVideo(inputPath: String): VideoTrackInfo? = withContext(Dispatchers.IO) {
        if (!File(inputPath).exists()) return@withContext null
        val p = probeVideoTrack(inputPath)
        if (p.videoMime == null && p.widthPx == 0 && p.heightPx == 0) return@withContext null

        // MediaExtractor is fast but omits KEY_PROFILE / colour-transfer for many in-app camera
        // captures, leaving bit depth / HDR undetermined (null). Rather than assume 8-bit SDR
        // (the fail-open that shipped undecodable 10-bit High-10 clips, #959), resolve the nulls
        // with an authoritative ffprobe read — so an in-budget 8-bit clip still passes through and
        // a 10-bit one is caught. If ffprobe also can't tell, the value stays null and the planner
        // fails closed (re-encodes) anyway.
        var bitDepth = p.bitDepth
        var isHdr = p.isHdr
        if (bitDepth == null || isHdr == null) {
            val tags = ffprobeColorTags(inputPath)
            if (bitDepth == null) bitDepth = bitDepthFromPixFmt(tags?.pixFmt)
            if (isHdr == null) isHdr = isHdrFromColorTags(tags?.colorTransfer, tags?.colorPrimaries)
        }
        VideoTrackInfo(p.videoMime, p.widthPx, p.heightPx, bitDepth, isHdr)
    }

    private data class ColorTags(
        val pixFmt: String?,
        val colorTransfer: String?,
        val colorPrimaries: String?,
    )

    /**
     * Authoritative colour-tag read via FFprobeKit's MediaInformation — the fallback when the fast
     * MediaExtractor probe leaves bit depth / HDR undetermined (#959). Returns null on any failure;
     * the caller then leaves the values null and the planner re-encodes to be safe.
     */
    private fun ffprobeColorTags(inputPath: String): ColorTags? = try {
        val info = FFprobeKit.getMediaInformation(inputPath)?.mediaInformation
        val stream = info?.streams?.firstOrNull { it.type == "video" }
        if (stream == null) null
        else ColorTags(
            pixFmt = stream.getStringProperty("pix_fmt"),
            colorTransfer = stream.getStringProperty("color_transfer"),
            colorPrimaries = stream.getStringProperty("color_primaries"),
        )
    } catch (e: Exception) {
        Log.w(TAG, "ffprobe colour-tag fallback failed for $inputPath", e)
        null
    }

    private fun probeVideoTrack(inputPath: String): VideoTrackProbe {
        var videoCount = 0
        var audioCount = 0
        var videoMime: String? = null
        var widthPx = 0
        var heightPx = 0
        var bitDepth: Int? = null
        var isHdr: Boolean? = null

        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> {
                        videoCount++
                        if (videoMime == null) {
                            videoMime = mime
                            widthPx = readDim(fmt, android.media.MediaFormat.KEY_WIDTH, "display-width")
                            heightPx = readDim(fmt, android.media.MediaFormat.KEY_HEIGHT, "display-height")
                            bitDepth = readBitDepth(fmt)
                            isHdr = readIsHdr(fmt)
                        }
                    }
                    mime.startsWith("audio/") -> audioCount++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "probeVideoTrack failed for $inputPath", e)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
        return VideoTrackProbe(videoCount, audioCount, videoMime, widthPx, heightPx, bitDepth, isHdr)
    }

    /**
     * Luma bit depth of the video track, inferred from the codec profile — or **null** when
     * `KEY_PROFILE` is absent (MediaExtractor omits it for many in-app camera captures). Null must
     * NOT be assumed 8-bit (that fail-open shipped 10-bit High-10 clips receivers can't decode,
     * #959): the caller resolves null via an authoritative ffprobe read. A present profile yields 10
     * for the known 10-bit profiles (H.264 High 10, HEVC Main 10, VP9 Profile 2/3), else 8.
     */
    private fun readBitDepth(fmt: android.media.MediaFormat): Int? {
        if (!fmt.containsKey(android.media.MediaFormat.KEY_PROFILE)) return null
        val profile = try {
            fmt.getInteger(android.media.MediaFormat.KEY_PROFILE)
        } catch (_: Exception) { return null }
        val tenBitProfiles = setOf(
            android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
            android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
            android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile2,
            android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile3,
            android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
            android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR,
        )
        return if (profile in tenBitProfiles) 10 else 8
    }

    /**
     * HDR detection mirroring Signal's MediaCodecCompat.isHdrVideo (see
     * [VideoThumbnailsMediaCodec]'s copy): a PQ/HLG colour-transfer, or static HDR10 / dynamic
     * HDR10+ metadata on the track. Returns **null** when none of those keys are present at all
     * (undeterminable — the caller resolves it via ffprobe rather than assuming SDR, #959).
     */
    private fun readIsHdr(fmt: android.media.MediaFormat): Boolean? {
        val hasTransfer = fmt.containsKey(android.media.MediaFormat.KEY_COLOR_TRANSFER)
        val hasStatic = fmt.containsKey(android.media.MediaFormat.KEY_HDR_STATIC_INFO)
        val hasPlus = android.os.Build.VERSION.SDK_INT >= 29 &&
            fmt.containsKey(android.media.MediaFormat.KEY_HDR10_PLUS_INFO)
        if (!hasTransfer && !hasStatic && !hasPlus) return null
        if (hasStatic || hasPlus) return true
        val transfer = try {
            fmt.getInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER)
        } catch (_: Exception) { return null }
        return transfer == android.media.MediaFormat.COLOR_TRANSFER_ST2084 ||
            transfer == android.media.MediaFormat.COLOR_TRANSFER_HLG
    }

    private fun readDim(format: android.media.MediaFormat, primary: String, display: String): Int {
        return try {
            if (format.containsKey(display)) format.getInteger(display)
            else format.getInteger(primary)
        } catch (_: Exception) {
            0
        }
    }

    // (probeDimensions + Int.toEven removed — output-dim math + even-rounding
    // now live in FfmpegCompressPlanner.computeOutputDims in commonMain.)

    /**
     * Async FFmpegKit bridged to a suspending coroutine. Returns the completed
     * [FFmpegSession] so the caller can inspect `returnCode` and friends.
     * Uses the async API to avoid sync `executeWithArguments`'s
     * re-entry-in-same-process crashes (see [compressVideo] for details).
     *
     * When [onStatistics] is non-null, the 4-arg overload is used so ffmpeg-kit
     * fires progress callbacks (~1-2 Hz) on its internal worker thread.
     */
    private suspend fun executeFfmpegAsync(
        args: Array<String>,
        onStatistics: ((Statistics) -> Unit)? = null,
    ): FFmpegSession =
        suspendCancellableCoroutine { cont ->
            val onComplete = FFmpegSessionCompleteCallback { sess ->
                if (cont.isActive) cont.resume(sess)
            }
            val session = if (onStatistics != null) {
                FFmpegKit.executeWithArgumentsAsync(
                    args,
                    onComplete,
                    null,
                    StatisticsCallback { stats -> stats?.let(onStatistics) },
                )
            } else {
                FFmpegKit.executeWithArgumentsAsync(args, onComplete)
            }
            cont.invokeOnCancellation {
                try { session.cancel() } catch (_: Exception) {}
            }
        }

    private fun progressFraction(timeMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else (timeMs.toFloat() / durationMs).coerceIn(0f, 1f)

    actual suspend fun segmentVideo(inputPath: String,
                                    onProgress: ((Float) -> Unit)?): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireApplicationContext()
            val file = File(inputPath)
            if (!file.exists()) return@withContext null

            val rotation = getRotationFromFile(inputPath)
            val absRot = kotlin.math.abs(((rotation % 360) + 360) % 360)
            val needsRotationFix = absRot == 90 || absRot == 270

            val outputDir = context.cacheDir
            val playlistName = "ffmpeg-segmented-${UUID.randomUUID()}.m3u8"
            val playlistPath = File(outputDir, playlistName).absolutePath

            val commandArgs = mutableListOf<String>()
            commandArgs.add("-y")
            commandArgs.add("-i")
            commandArgs.add(inputPath)

            if (!needsRotationFix) {
                // Pure copy — fastest, no rotation needed
                commandArgs.add("-codec:v")
                commandArgs.add("copy")
                commandArgs.add("-codec:a")
                commandArgs.add("copy")
            } else {
                // Re-encode only when rotated → preserves rotation + smaller file
                commandArgs.add("-c:v")
                commandArgs.add("libx264")
                commandArgs.add("-preset")
                commandArgs.add("veryfast")
                commandArgs.add("-crf")
                commandArgs.add("23")
                commandArgs.add("-g")
                commandArgs.add("30")
                commandArgs.add("-bf")
                commandArgs.add("2")
                commandArgs.add("-c:a")
                commandArgs.add("copy")
            }

            commandArgs.add("-hls_time")
            commandArgs.add("6")
            commandArgs.add("-hls_list_size")
            commandArgs.add("0")
            commandArgs.add("-hls_flags")
            commandArgs.add("single_file")
            commandArgs.add("-f")
            commandArgs.add("hls")
            commandArgs.add(playlistPath)

            val command = commandArgs.joinToString(" ")
            Log.d(TAG, "Segment command: $command")

            val session = FFmpegKit.execute(command)
            if (ReturnCode.isSuccess(session.returnCode)) {
                val segmentPath = playlistPath.replace(".m3u8", ".ts")
                return@withContext Pair(playlistPath, segmentPath)
            } else {
                Log.e(TAG, "Segmentation failed: ${session.failStackTrace}")
                // Delete the partial playlist + segment left behind (see #5).
                deleteFailedFfmpegOutput(playlistPath)
                deleteFailedFfmpegOutput(playlistPath.replace(".m3u8", ".ts"))
                return@withContext null
            }
        }

    actual suspend fun cacheInputVideo(fileName: String, data: ByteArray): String =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireApplicationContext()
            val cacheFile = File(context.cacheDir, "input_$fileName")
            cacheFile.writeBytes(data)
            return@withContext cacheFile.absolutePath
        }

    actual suspend fun segmentAndEncryptVideo(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: ((Float) -> Unit)?
    ): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireApplicationContext()
            val inputFile = File(inputPath)
            if (!inputFile.exists()) return@withContext null

            val rotation = getRotationFromFile(inputPath)
            val absRot = kotlin.math.abs(((rotation % 360) + 360) % 360)
            val needsRotationFix = absRot == 90 || absRot == 270

            val outputDir = File(
                context.cacheDir,
                "hls_${UUID.randomUUID()}"
            ).apply { mkdirs() }

            val playlistPath = File(outputDir, "index.m3u8").absolutePath
            val segmentPath = File(outputDir, "index.ts").absolutePath

            // 🔐 Generate key + keyinfo files
            val keyInfoFile = generateHlsKeyInfoFile(
                outputDir = outputDir,
                aesKey = keyHeader.aesKey.unsafeBytes,
                iv = keyHeader.iv
            )

            val args = mutableListOf<String>()
            args.add("-y")
            args.add("-i")
            args.add(inputPath)

            if (!needsRotationFix) {
                args.addAll(listOf("-codec:v", "copy", "-codec:a", "copy"))
            } else {
                args.addAll(
                    listOf(
                        "-c:v", "libx264",
                        "-preset", "veryfast",
                        "-crf", "23",
                        "-g", "30",
                        "-bf", "2",
                        "-c:a", "copy"
                    )
                )
            }

            args.addAll(
                listOf(
                    "-hls_time", "6",
                    "-hls_list_size", "0",
                    "-hls_flags", "single_file",
                    "-hls_key_info_file", keyInfoFile.absolutePath,
                    "-f", "hls",
                    "-hls_segment_filename", segmentPath,
                    playlistPath
                )
            )

            Log.d(TAG, "Segment+Encrypt args: $args")

            // Re-probe on the segmentation input — the upstream compressor may
            // have trimmed, so the original input's duration is no longer the
            // denominator for progress scaling.
            val durationMs = getDurationMs(inputPath)

            val session = try {
                executeFfmpegAsync(args.toTypedArray()) { stats ->
                    onProgress?.invoke(progressFraction(stats.time.toLong(), durationMs))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "segmentAndEncryptVideo crashed", e)
                deleteFailedFfmpegOutput(outputDir.absolutePath)
                return@withContext null
            }
            if (ReturnCode.isSuccess(session.returnCode)) {
                // Segmentation done — FFmpeg has consumed the key material. Delete it now
                // so the plaintext AES key doesn't linger in the cache dir (see #7).
                deleteHlsKeyMaterial(outputDir.absolutePath)
                Pair(playlistPath, segmentPath)
            } else {
                Log.e(TAG, "Segment+Encrypt failed: ${session.failStackTrace}")
                // Delete the whole hls_<uuid>/ dir — partial segments + key material (see #5).
                deleteFailedFfmpegOutput(outputDir.absolutePath)
                null
            }
        }

    actual suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val args = arrayOf(
                "-y",
                "-allowed_extensions", "ALL",
                "-i", playlistPath,
                "-c", "copy",
                "-bsf:a", "aac_adtstoasc",
                "-movflags", "+faststart",
                outputPath
            )
            Log.d(TAG, "Remux HLS→MP4 args: ${args.joinToString(" ")}")
            val session = FFmpegKit.executeWithArguments(args)
            val ok = ReturnCode.isSuccess(session.returnCode)
            if (!ok) Log.e(TAG, "Remux failed: ${session.failStackTrace}")
            ok
        }

    fun generateHlsKeyInfoFile(
        outputDir: File,
        aesKey: ByteArray,
        iv: ByteArray,
        keyFileName: String = "enc.key",
        keyInfoFileName: String = "keyinfo.txt"
    ): File {
        require(aesKey.size == 16) { "AES key must be 16 bytes (AES-128)" }
        require(iv.size == 16) { "IV must be 16 bytes" }

        outputDir.mkdirs()

        // 1️⃣ Write raw AES key (binary)
        val keyFile = File(outputDir, keyFileName)
        keyFile.writeBytes(aesKey)

        // 2️⃣ Convert IV to hex (no 0x prefix)
        val ivHex = iv.joinToString("") { "%02x".format(it) }

        // 3️⃣ Write key info file (consumed by FFmpeg)
        val keyInfoFile = File(outputDir, keyInfoFileName)
        keyInfoFile.writeText(
            """
        $keyFileName
        ${keyFile.absolutePath}
        $ivHex
        """.trimIndent()
        )

        return keyInfoFile
    }
}
