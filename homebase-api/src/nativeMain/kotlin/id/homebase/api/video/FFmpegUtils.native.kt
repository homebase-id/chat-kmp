package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import kotlin.math.roundToLong
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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

    private val MAX_BITRATE = 3_000_000L
    private val MAX_WIDTH = 1280

    actual suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
    ): String? =
            // TODO map quality → -b:v / -vf scale. Currently uses fixed MAX_BITRATE/MAX_WIDTH.
            withContext(Dispatchers.IO) {
                val fileManager = NSFileManager.defaultManager

                // Validate input file exists
                if (!fileManager.fileExistsAtPath(inputPath)) {
                    println("Docs: Input file not found: $inputPath")
                    return@withContext null
                }

                val hasTrim = trimStartMs != null || trimEndMs != null

                if (!hasTrim && isAlreadyOptimal(inputPath)) {
                    println("Docs: Video already optimal — skipping compression")
                    return@withContext null
                }

                val cacheDir = getCacheDirectory()
                val outputPath = "$cacheDir/compressed_${getUniqueId(inputPath)}.mp4"

                // Remove existing file if any
                if (fileManager.fileExistsAtPath(outputPath)) {
                    fileManager.removeItemAtPath(outputPath, null)
                }

                // Build optional trim args. -ss before -i for fast input seek; -t for duration.
                val trimPre = if (trimStartMs != null && trimStartMs > 0) {
                    "-ss ${formatSecondsForCli(trimStartMs)} "
                } else ""

                val trimDurMs = if (trimEndMs != null) {
                    (trimEndMs - (trimStartMs ?: 0L)).coerceAtLeast(0L)
                } else null
                val trimMid = if (trimDurMs != null) {
                    "-t ${formatSecondsForCli(trimDurMs)} "
                } else ""

                // Use hardware encoder (VideoToolbox) for speed, fall back to libx264
                val command =
                        "-y ${trimPre}-i \"$inputPath\" ${trimMid}-c:v h264_videotoolbox -b:v 3000k -vf scale=min(1280\\,iw):-2 \"$outputPath\""

                val result = bridge.executeFFmpeg(command)

                if (result.isSuccess) {
                    outputPath
                } else {
                    // Fall back to software encoder if hardware fails
                    println("Docs: Hardware encoder failed, falling back to libx264: ${result.failStackTrace}")
                    val fallbackCommand =
                            "-y ${trimPre}-i \"$inputPath\" ${trimMid}-c:v libx264 -b:v 3000k -vf scale=min(1280\\,iw):-2 -preset fast \"$outputPath\""
                    val fallbackResult = bridge.executeFFmpeg(fallbackCommand)
                    if (fallbackResult.isSuccess) {
                        outputPath
                    } else {
                        // Both encoders failed — delete the partial/empty output (see #5).
                        deleteFailedFfmpegOutput(outputPath)
                        null
                    }
                }
            }

    /**
     * "Already optimal" = h264 + width ≤ MAX_WIDTH + average bitrate ≤ MAX_BITRATE.
     * Bitrate is computed from fileSize / duration. The bridge's
     * `videoStream.bitrate` is optional in the underlying ffprobe output and was
     * not always populated, which used to force unnecessary re-encodes.
     */
    private suspend fun isAlreadyOptimal(inputPath: String): Boolean {
        val mediaInfo = bridge.getMediaInformation(inputPath) ?: return false
        val videoStream = mediaInfo.streams.firstOrNull { it.type == "video" }
            ?: return false
        val codec = videoStream.codec?.lowercase() ?: return false
        val width = videoStream.width ?: return false

        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(inputPath, null)
        val sizeBytes = (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
        val durationMs = getDurationMs(inputPath)
        if (sizeBytes <= 0L || durationMs <= 0L) return false
        val avgBitrate = sizeBytes * 8L * 1000L / durationMs

        return codec == "h264" && width <= MAX_WIDTH && avgBitrate <= MAX_BITRATE
    }

    private fun formatSecondsForCli(ms: Long): String {
        val whole = ms / 1000
        val frac = ms % 1000
        return "$whole.${frac.toString().padStart(3, '0')}"
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

                val result = bridge.executeFFmpeg(command)

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
