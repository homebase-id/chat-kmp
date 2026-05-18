package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import kotlin.coroutines.resume
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

    actual suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
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

        // Probe via the Swift FFmpegKit bridge.
        val mediaInfo = bridge.getMediaInformation(inputPath)
        val videoStream = mediaInfo?.streams?.firstOrNull { it.type == "video" }
        val widthPx = videoStream?.width ?: 0
        val heightPx = videoStream?.height ?: 0
        val codecMime = videoStream?.codec  // ffprobe short form, e.g. "h264"

        val attrs = fileManager.attributesOfItemAtPath(inputPath, null)
        val inputBytes = (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
        val durationMs = getDurationMs(inputPath)

        // Try hardware encoder first; fall back to libx264 if it fails. The
        // planner takes the encoder name and emits libx264-only flags
        // (-preset veryfast) only when appropriate.
        for ((index, encoder) in listOf("h264_videotoolbox", "libx264").withIndex()) {
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
                encoder = encoder,
            )

            if (plan.skipReason != null) {
                println("Docs: compressVideo: AlreadyOptimal — ${plan.skipReason}")
                return@withContext null
            }

            // FFmpegKit's iOS executeFFmpeg(String) does pseudo-shell quoting,
            // so wrap any arg containing whitespace in double quotes. The
            // planner emits no special chars beyond the input/output paths
            // (which app cacheDir-rooted), so this naive quoting is sufficient.
            val command = plan.args.joinToString(" ") { if (' ' in it) "\"$it\"" else it }

            val result = executeFfmpegWithProgress(command, durationMs, onProgress)
            if (result.isSuccess) {
                return@withContext outputPath
            }
            if (index == 0) {
                println("Docs: Hardware encoder $encoder failed, trying next: ${result.failStackTrace}")
            } else {
                println("Docs: Software encoder $encoder also failed: ${result.failStackTrace}")
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
     * [progressFraction]. Cancellation calls the bridge-wide cancel — fine for
     * the current single-job flow (see [FFmpegKitBridge.cancelAllFFmpegSessions]
     * docs for the per-session follow-up).
     */
    private suspend fun executeFfmpegWithProgress(
        command: String,
        durationMs: Long,
        onProgress: ((Float) -> Unit)?,
    ): FFmpegResult = suspendCancellableCoroutine { cont ->
        bridge.executeFFmpegAsync(
            command = command,
            onProgress = { timeMs ->
                onProgress?.invoke(progressFraction(timeMs, durationMs))
            },
            onComplete = { result ->
                if (cont.isActive) cont.resume(result)
            },
        )
        cont.invokeOnCancellation {
            try { bridge.cancelAllFFmpegSessions() } catch (_: Exception) {}
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
