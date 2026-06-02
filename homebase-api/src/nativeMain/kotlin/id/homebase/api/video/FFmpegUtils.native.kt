package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.*

/**
 * Holder for the FFmpegKit bridge implementation. Must be set from Swift before any FFmpeg
 * operations are called.
 */
object FFmpegKitBridgeHolder {
    private var _bridge: FFmpegKitBridge? = null

    fun setBridge(bridge: FFmpegKitBridge) {
        _bridge = bridge
    }

    fun getBridge(): FFmpegKitBridge {
        return _bridge
                ?: throw IllegalStateException(
                        "FFmpegKitBridge has not been initialized. " +
                                "Call FFmpegKitBridgeHolder.setBridge() from Swift at app startup."
                )
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual object FFmpegUtils {

    private val bridge: FFmpegKitBridge
        get() = FFmpegKitBridgeHolder.getBridge()

    private var cachedFfmpegVersion: String? = null
    private var ffmpegVersionProbed: Boolean = false

    actual suspend fun getFfmpegVersion(): String? = withContext(Dispatchers.IO) {
        if (ffmpegVersionProbed) return@withContext cachedFfmpegVersion
        val v = try {
            parseFfmpegVersionBanner(bridge.getFfmpegVersionBanner())
        } catch (e: Exception) {
            println("Docs: getFfmpegVersion failed: ${e.message}")
            null
        }
        cachedFfmpegVersion = v
        ffmpegVersionProbed = true
        v
    }

    actual fun getUniqueId(filePath: String): String {
        // Match Android/Desktop: use file name + size for consistent ID generation
        val fileManager = NSFileManager.defaultManager
        val attrs = fileManager.attributesOfItemAtPath(filePath, null)
        val fileSize = (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
        val fileName = filePath.substringAfterLast("/")
        return "${fileName}_${fileSize}".hashCode().toString()
    }

    actual suspend fun grabThumbnail(inputPath: String): String? =
            withContext(Dispatchers.IO) {
                val fileManager = NSFileManager.defaultManager

                // Validate input file exists
                if (!fileManager.fileExistsAtPath(inputPath)) {
                    println("Docs: Input file not found: $inputPath")
                    return@withContext null
                }

                val cacheDir = getCacheDirectory()
                val outputPath = "$cacheDir/thumb_${getUniqueId(inputPath)}.jpg"

                // Remove existing file if any
                if (fileManager.fileExistsAtPath(outputPath)) {
                    fileManager.removeItemAtPath(outputPath, null)
                }

                // Example command: -i input.mp4 -ss 00:00:01.000 -vframes 1 output.jpg
                val command = "-i \"$inputPath\" -ss 00:00:01.000 -vframes 1 \"$outputPath\""
                val result = bridge.executeFFmpeg(command)

                if (result.isSuccess) {
                    outputPath
                } else {
                    println("Docs: Error grabbing thumbnail: ${result.failStackTrace}")
                    null
                }
            }

    actual suspend fun getRotationFromFile(filePath: String): Int =
            withContext(Dispatchers.IO) {
                try {
                    val mediaInfo = bridge.getMediaInformation(filePath) ?: return@withContext 0

                    // Find video stream and extract rotation
                    for (stream in mediaInfo.streams) {
                        if (stream.type != "video") continue

                        // Check rotation from tags or side data
                        val rotation = stream.rotation ?: 0
                        if (rotation in -360..360) {
                            return@withContext rotation
                        }
                    }

                    0
                } catch (e: Exception) {
                    println("Docs: Error getting rotation from file: ${e.message}")
                    0
                }
            }

    actual suspend fun probeVideo(inputPath: String): VideoTrackInfo? = withContext(Dispatchers.IO) {
        if (!NSFileManager.defaultManager.fileExistsAtPath(inputPath)) return@withContext null
        val p = probeVideoNative(inputPath) ?: return@withContext null
        VideoTrackInfo(p.codec, p.widthPx, p.heightPx, p.bitDepth, p.isHdr)
    }

    private data class NativeVideoProbe(
        val codec: String?,
        val widthPx: Int,
        val heightPx: Int,
        val rotation: Int,
        val bitDepth: Int,
        val isHdr: Boolean,
    )

    /**
     * Unified video probe. Prefers FFmpegKit's ffprobe (rich: pix_fmt / bit-depth / HDR),
     * but falls back to AVFoundation when ffprobe yields no usable video stream.
     *
     * FFmpegKit's `getMediaInformation` returns nil for modern iPhone captures (4K HEVC +
     * `apac` spatial audio + `mebx` metadata tracks): verified that `-v verbose` logs fine
     * but the JSON show-streams output is empty, so the planner was fed width=0 /
     * no-rotation — a degenerate command that crashed h264_videotoolbox in ffmpeg's
     * print_report. AVFoundation reads these files natively (it already backs
     * [getDurationMs] and poster extraction), mirroring how the Android actual probes via
     * MediaExtractor / MediaMetadataRetriever rather than ffprobe.
     */
    private fun probeVideoNative(inputPath: String): NativeVideoProbe? {
        val v = bridge.getMediaInformation(inputPath)?.streams?.firstOrNull { it.type == "video" }
        if (v != null && (v.width ?: 0) > 0 && (v.height ?: 0) > 0) {
            val pixFmt = v.pixelFormat?.lowercase().orEmpty()
            val transfer = v.colorTransfer?.lowercase().orEmpty()
            val primaries = v.colorPrimaries?.lowercase().orEmpty()
            return NativeVideoProbe(
                codec = v.codec,
                widthPx = v.width ?: 0,
                heightPx = v.height ?: 0,
                rotation = v.rotation ?: 0,
                bitDepth = bitDepthFromPixFmt(pixFmt),
                isHdr = transfer == "smpte2084" || transfer == "arib-std-b67" ||
                    primaries.startsWith("bt2020"),
            )
        }
        return avProbeVideoTrack(inputPath)
    }

    private fun bitDepthFromPixFmt(pixFmt: String): Int = when {
        pixFmt.isBlank() -> 8
        "12" in pixFmt -> 12
        "10" in pixFmt || pixFmt.startsWith("p010") -> 10
        else -> 8
    }

    /** AVFoundation track probe — native iOS metadata for files ffprobe can't read. */
    @OptIn(ExperimentalForeignApi::class)
    private fun avProbeVideoTrack(inputPath: String): NativeVideoProbe? {
        val url = avUrl(inputPath) ?: return null
        val asset = AVURLAsset.URLAssetWithURL(url, options = null)
        val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
            ?: return null
        val (w, h) = track.naturalSize.useContents { width.toInt() to height.toInt() }
        if (w <= 0 || h <= 0) return null
        val rotation = track.preferredTransform.useContents {
            val deg = (atan2(b, a) * 180.0 / PI).roundToInt()
            ((deg % 360) + 360) % 360
        }
        // codec=null on the AVFoundation path: these files are re-encoded regardless
        // (HEVC -> H.264), so the already-optimal short-circuit must stay off, and the
        // real output codec is filled in post-encode. bitDepth/HDR default to 8-bit SDR
        // (the planner's safe yuv420p pin); 10-bit/HDR detection here is a follow-up.
        return NativeVideoProbe(codec = null, widthPx = w, heightPx = h, rotation = rotation, bitDepth = 8, isHdr = false)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun avUrl(inputPath: String): NSURL? =
        if (inputPath.startsWith("file://")) NSURL.URLWithString(inputPath)
        else NSURL.fileURLWithPath(inputPath)

    actual suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
        allowTenBit: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(inputPath)) {
            println("Docs: Input file not found: $inputPath")
            return@withContext null
        }

        val effectiveTrimStart = if (trimStartMs != null && trimEndMs != null) trimStartMs else null
        val effectiveTrimEnd = if (trimStartMs != null && trimEndMs != null) trimEndMs else null

        val cacheDir = getCacheDirectory()
        val outputPath = "$cacheDir/compressed_${getUniqueId(inputPath)}.mp4"
        if (fileManager.fileExistsAtPath(outputPath)) {
            fileManager.removeItemAtPath(outputPath, null)
        }

        // Probe metadata: prefer FFmpegKit's ffprobe (rich: pix_fmt/bit-depth/HDR), fall
        // back to AVFoundation when ffprobe can't read the file (modern iPhone captures).
        // See probeVideoNative. Raw container dims + rotation feed the planner so portrait
        // captures aren't squished; bit depth/HDR drive the 8-bit-output pin.
        val probe = probeVideoNative(inputPath)
        val widthPx = probe?.widthPx ?: 0
        val heightPx = probe?.heightPx ?: 0
        val codecMime = probe?.codec  // short form, e.g. "h264" / "hevc"; null forces re-encode
        val rotation = probe?.rotation ?: 0
        val bitDepth = probe?.bitDepth ?: 8
        val isHdr = probe?.isHdr ?: false

        val attrs = fileManager.attributesOfItemAtPath(inputPath, null)
        val inputBytes = (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
        val durationMs = getDurationMs(inputPath)

        // Try hardware encoder first; fall back to libx264 if it fails. The
        // planner takes the encoder name and emits libx264-only flags
        // (-preset veryfast) only when appropriate.
        //
        // When emitting 10-bit (allowTenBit + >8-bit source), skip
        // h264_videotoolbox entirely: Apple's H.264 hardware encoder cannot
        // produce 10-bit (High 10) output, so only libx264 can honour the
        // yuv420p10le pin.
        val encoders =
            if (allowTenBit && bitDepth > 8) listOf("libx264")
            else listOf("h264_videotoolbox", "libx264")
        for ((index, encoder) in encoders.withIndex()) {
            val plan = FfmpegCompressPlanner.plan(
                inputPath = inputPath,
                outputPath = outputPath,
                quality = quality,
                trimStartMs = effectiveTrimStart,
                trimEndMs = effectiveTrimEnd,
                probedWidthPx = widthPx,
                probedHeightPx = heightPx,
                probedCodecMime = codecMime,
                inputDurationMs = durationMs,
                inputBytes = inputBytes,
                rotationDegrees = rotation,
                encoder = encoder,
                probedBitDepth = bitDepth,
                probedIsHdr = isHdr,
                allowTenBit = allowTenBit,
            )

            if (plan.skipReason != null) {
                println("Docs: compressVideo: AlreadyOptimal — ${plan.skipReason}")
                return@withContext null
            }

            // When using a VideoToolbox encoder, also HW-decode the input.
            // Without this, HEVC sources are software-decoded which is 3-4x
            // slower on iPhone (18s → 5s for a 15s 1080p60 HEVC clip).
            val args = if (encoder.contains("videotoolbox")) {
                val mutable = plan.args.toMutableList()
                val iIdx = mutable.indexOf("-i")
                if (iIdx >= 0) {
                    mutable.add(iIdx, "videotoolbox")
                    mutable.add(iIdx, "-hwaccel")
                }
                mutable
            } else {
                plan.args
            }

            val command = args.joinToString(" ") { if (' ' in it) "\"$it\"" else it }

            val result = executeFfmpegWithProgress(command, durationMs, onProgress)
            if (result.isSuccess) {
                return@withContext outputPath
            }
            if (index < encoders.lastIndex) {
                println("Docs: Encoder $encoder failed, trying next: ${result.failStackTrace}")
            } else {
                println("Docs: Encoder $encoder (last) also failed: ${result.failStackTrace}")
            }
        }

        // Both encoders failed — delete the partial/empty output (see #5).
        deleteFailedFfmpegOutput(outputPath)
        null
    }

    actual suspend fun segmentVideo(
            inputPath: String,
            onProgress: ((Float) -> Unit)?
    ): Pair<String, String>? =
            withContext(Dispatchers.IO) {
                val cacheDir = getCacheDirectory()
                val outputDir = "$cacheDir/hls_${getUniqueId(inputPath)}"

                val fileManager = NSFileManager.defaultManager
                if (!fileManager.fileExistsAtPath(outputDir)) {
                    fileManager.createDirectoryAtPath(outputDir, true, null, null)
                }

                val indexPath = "$outputDir/index.m3u8"
                val segmentPath = "$outputDir/index.ts"

                val rotation = getRotationFromFile(inputPath)
                val absRot = kotlin.math.abs(((rotation % 360) + 360) % 360)
                val needsRotationFix = absRot == 90 || absRot == 270

                val baseCommand =
                        if (!needsRotationFix) {
                            "-i \"$inputPath\" -codec:v copy -codec:a copy"
                        } else {
                            "-i \"$inputPath\" -c:v libx264 -preset veryfast -crf 23 -g 30 -bf 2 -c:a copy"
                        }

                val command =
                        "$baseCommand -hls_time 6 -hls_list_size 0 -hls_flags single_file -f hls -hls_segment_filename \"$segmentPath\" \"$indexPath\""

                val result = bridge.executeFFmpeg(command)

                if (result.isSuccess) {
                    Pair(indexPath, segmentPath)
                } else {
                    println("Docs: Error segmenting video: ${result.failStackTrace}")
                    // Delete the leftover hls_<uuid>/ dir (see #5).
                    deleteFailedFfmpegOutput(outputDir)
                    null
                }
            }

    private fun progressFraction(timeMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else (timeMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * Bridges the Swift async ffmpeg API to a suspending coroutine and converts
     * ffmpeg-kit's elapsed media time (ms) into a 0..1 progress fraction via
     * [progressFraction]. Cancellation tears down ONLY this job's session via
     * [FFmpegKitBridge.cancelFFmpegSession] — not the engine-wide
     * [FFmpegKitBridge.cancelAllFFmpegSessions], which would also kill any
     * concurrent FFmpegKit work (e.g. a thumbnail-strip extraction, or another
     * compress/segment job). Mirrors the per-session fix the thumbnail decoder
     * already uses in [FFmpegKitVideoDecoder.extractThumbnailStrip].
     */
    private suspend fun executeFfmpegWithProgress(
        command: String,
        durationMs: Long,
        onProgress: ((Float) -> Unit)?,
    ): FFmpegResult = suspendCancellableCoroutine { cont ->
        val sessionId = bridge.executeFFmpegAsync(
            command = command,
            onProgress = { timeMs ->
                onProgress?.invoke(progressFraction(timeMs, durationMs))
            },
            onComplete = { result ->
                if (cont.isActive) cont.resume(result)
            },
        )
        cont.invokeOnCancellation {
            if (sessionId >= 0) {
                try { bridge.cancelFFmpegSession(sessionId) } catch (_: Exception) {}
            }
        }
    }

    private fun getCacheDirectory(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        return paths.firstOrNull() as? String ?: NSTemporaryDirectory()
    }

    actual suspend fun cacheInputVideo(fileName: String, data: ByteArray): String =
            withContext(Dispatchers.IO) {
                val cacheDir = getCacheDirectory()
                val outputPath = "$cacheDir/input_$fileName"

                // Use memScoped to allocate buffer and copy bytes
                memScoped {
                    val buffer = allocArrayOf(data)
                    // dataWithBytes expects CPointer and length
                    val nsData = NSData.dataWithBytes(bytes = buffer, length = data.size.toULong())
                    nsData.writeToFile(outputPath, true)
                }

                outputPath
            }

    actual suspend fun segmentAndEncryptVideo(
            inputPath: String,
            keyHeader: KeyHeader,
            onProgress: ((Float) -> Unit)?
    ): Pair<String, String>? =
            withContext(Dispatchers.IO) {
                val fileManager = NSFileManager.defaultManager
                if (!fileManager.fileExistsAtPath(inputPath)) {
                    println("Docs: Input file not found: $inputPath")
                    return@withContext null
                }

                val cacheDir = getCacheDirectory()
                val outputDir = "$cacheDir/hls_${getUniqueId(inputPath)}"

                if (!fileManager.fileExistsAtPath(outputDir)) {
                    fileManager.createDirectoryAtPath(outputDir, true, null, null)
                }

                val indexPath = "$outputDir/index.m3u8"
                val segmentPath = "$outputDir/index.ts"

                // 🔐 Generate key info file
                val keyInfoFilePath =
                        generateHlsKeyInfoFile(
                                outputDir = outputDir,
                                aesKey = keyHeader.aesKey.unsafeBytes,
                                iv = keyHeader.iv
                        )

                val rotation = getRotationFromFile(inputPath)
                val absRot = kotlin.math.abs(((rotation % 360) + 360) % 360)
                val needsRotationFix = absRot == 90 || absRot == 270

                val baseCommand =
                        if (!needsRotationFix) {
                            "-i \"$inputPath\" -codec:v copy -codec:a copy"
                        } else {
                            "-i \"$inputPath\" -c:v libx264 -preset veryfast -crf 23 -g 30 -bf 2 -c:a copy"
                        }

                val command =
                        "$baseCommand -hls_time 6 -hls_list_size 0 -hls_flags single_file -hls_key_info_file \"$keyInfoFilePath\" -f hls -hls_segment_filename \"$segmentPath\" \"$indexPath\""

                // Re-probe on the segmentation input — the upstream compressor
                // may have trimmed, so the original input's duration is no
                // longer the denominator for progress scaling.
                val durationMs = getDurationMs(inputPath)

                val result = executeFfmpegWithProgress(command, durationMs, onProgress)

                if (result.isSuccess) {
                    // Segmentation done — FFmpeg has consumed the key material. Delete it now
                    // so the plaintext AES key doesn't linger in the cache dir (see #7).
                    deleteHlsKeyMaterial(outputDir)
                    Pair(indexPath, segmentPath)
                } else {
                    println("Docs: Error segment+encrypt video: ${result.failStackTrace}")
                    // Delete the whole hls_<uuid>/ dir — partial segments + key material (see #5).
                    deleteFailedFfmpegOutput(outputDir)
                    null
                }
            }

    actual suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean =
            withContext(Dispatchers.IO) {
                val fileManager = NSFileManager.defaultManager
                if (fileManager.fileExistsAtPath(outputPath)) {
                    fileManager.removeItemAtPath(outputPath, null)
                }
                val command =
                        "-y -allowed_extensions ALL -i \"$playlistPath\" -c copy -bsf:a aac_adtstoasc -movflags +faststart \"$outputPath\""
                val result = bridge.executeFFmpeg(command)
                if (!result.isSuccess) {
                    println("Docs: Error remuxing HLS→MP4: ${result.failStackTrace}")
                }
                result.isSuccess
            }

    fun generateHlsKeyInfoFile(
            outputDir: String,
            aesKey: ByteArray,
            iv: ByteArray,
            keyFileName: String = "enc.key",
            keyInfoFileName: String = "keyinfo.txt"
    ): String {
        require(aesKey.size == 16) { "AES key must be 16 bytes (AES-128)" }
        require(iv.size == 16) { "IV must be 16 bytes" }

        val fileManager = NSFileManager.defaultManager

        // Ensure directory exists
        if (!fileManager.fileExistsAtPath(outputDir)) {
            fileManager.createDirectoryAtPath(outputDir, true, null, null)
        }

        // --- write key file (binary) ---
        val keyFilePath = "$outputDir/$keyFileName"
        memScoped {
            val buffer = allocArrayOf(aesKey)
            val nsData = NSData.dataWithBytes(buffer, aesKey.size.toULong())
            nsData.writeToFile(keyFilePath, true)
        }

        // --- IV to hex ---
        val ivHex =
                iv.joinToString("") { byte ->
                    val hex = (byte.toInt() and 0xFF).toString(16)
                    if (hex.length == 1) "0$hex" else hex
                }

        // --- write key info file ---
        val keyInfoFilePath = "$outputDir/$keyInfoFileName"
        val keyInfoContents =
                """
        $keyFileName
        $keyFilePath
        $ivHex
    """.trimIndent()

        NSString.create(string = keyInfoContents)
                .writeToFile(keyInfoFilePath, true, NSUTF8StringEncoding, null)

        return keyInfoFilePath
    }

    actual suspend fun getDurationMs(inputPath: String): Long {
        // Defensive: handle both raw paths and file:// URLs. fileURLWithPath
        // would double-encode an already-formed URL; URLWithString won't
        // accept a bare path. Same pattern as LocalVideoPlayerSurface.native.kt.
        val url = if (inputPath.startsWith("file://"))
            NSURL.URLWithString(inputPath)!!
        else
            NSURL.fileURLWithPath(inputPath)
        val asset = AVURLAsset.URLAssetWithURL(url, options = null)

        val durationSeconds = CMTimeGetSeconds(asset.duration)
        if (durationSeconds.isNaN() || durationSeconds <= 0) return 0L

        return (durationSeconds * 1000.0).roundToLong()
    }
}
