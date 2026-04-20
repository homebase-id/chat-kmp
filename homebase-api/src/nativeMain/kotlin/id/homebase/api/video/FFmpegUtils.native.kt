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

    actual suspend fun compressVideo(inputPath: String, onProgress: ((Float) -> Unit)?): String? =
            withContext(Dispatchers.IO) {
                val fileManager = NSFileManager.defaultManager

                // Validate input file exists
                if (!fileManager.fileExistsAtPath(inputPath)) {
                    println("Docs: Input file not found: $inputPath")
                    return@withContext null
                }

                // Check if compression is needed
                val mediaInfo = bridge.getMediaInformation(inputPath)
                val videoStream = mediaInfo?.streams?.firstOrNull { it.type == "video" }
                val codec = videoStream?.codec?.lowercase()
                val bitrate = videoStream?.bitrate
                val width = videoStream?.width

                val isH264 = codec == "h264"
                val bitrateOk = bitrate != null && bitrate <= MAX_BITRATE
                val widthOk = width != null && width <= MAX_WIDTH

                if (isH264 && bitrateOk && widthOk) {
                    println("Docs: Video already optimal (h264, ${bitrate}bps, ${width}px) — skipping compression")
                    return@withContext null // null means "use original"
                }

                val cacheDir = getCacheDirectory()
                val outputPath = "$cacheDir/compressed_${getUniqueId(inputPath)}.mp4"

                // Remove existing file if any
                if (fileManager.fileExistsAtPath(outputPath)) {
                    fileManager.removeItemAtPath(outputPath, null)
                }

                // Use hardware encoder (VideoToolbox) for speed, fall back to libx264
                val command =
                        "-y -i \"$inputPath\" -c:v h264_videotoolbox -b:v 3000k -vf scale=min(1280\\,iw):-2 \"$outputPath\""

                val result = bridge.executeFFmpeg(command)

                if (result.isSuccess) {
                    outputPath
                } else {
                    // Fall back to software encoder if hardware fails
                    println("Docs: Hardware encoder failed, falling back to libx264: ${result.failStackTrace}")
                    val fallbackCommand =
                            "-y -i \"$inputPath\" -c:v libx264 -b:v 3000k -vf scale=min(1280\\,iw):-2 -preset fast \"$outputPath\""
                    val fallbackResult = bridge.executeFFmpeg(fallbackCommand)
                    if (fallbackResult.isSuccess) outputPath else null
                }
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
                    Pair(indexPath, segmentPath)
                } else {
                    println("Docs: Error segment+encrypt video: ${result.failStackTrace}")
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
        val url = NSURL.fileURLWithPath(inputPath)
        val asset = AVURLAsset.URLAssetWithURL(url, options = null)

        val durationSeconds = CMTimeGetSeconds(asset.duration)
        if (durationSeconds.isNaN() || durationSeconds <= 0) return 0L

        return (durationSeconds * 1000.0).roundToLong()
    }
}
