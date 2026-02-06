package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
actual object FFmpegUtils {
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
            val session = FFmpegKit.execute(command)

            if (ReturnCode.isSuccess(session?.getReturnCode())) {
                outputPath
            } else {
                println("Docs: Error grabbing thumbnail: ${session?.getFailStackTrace()}")
                null
            }
        }

    actual suspend fun getRotationFromFile(filePath: String): Int =
        withContext(Dispatchers.IO) {
            try {
                val mediaInformationSession = FFprobeKit.getMediaInformation(filePath)
                val mediaInformation =
                    mediaInformationSession?.getMediaInformation() ?: return@withContext 0

                val streams = mediaInformation.getStreams() ?: return@withContext 0

                // Iterate through streams to find video stream and extract rotation
                for (stream in streams) {
                    val streamInfo = stream as? StreamInformation ?: continue

                    // Only process video streams
                    if (streamInfo.getType() != "video") continue

                    // Method 1: Check the 'rotate' tag (older videos)
                    @Suppress("UNCHECKED_CAST") val tags = streamInfo.getTags()
                    if (tags != null) {
                        val rotateValue = tags["rotate"] as? String
                        if (rotateValue != null) {
                            val rotation = rotateValue.toIntOrNull() ?: 0
                            if (rotation in -360..360) {
                                return@withContext rotation
                            }
                        }
                    }

                    // Method 2: Check side_data_list for Display Matrix (newer videos)
                    @Suppress("UNCHECKED_CAST")
                    val sideDataList = streamInfo.getProperty("side_data_list") as? List<*>
                    if (sideDataList != null) {
                        for (sideData in sideDataList) {
                            @Suppress("UNCHECKED_CAST")
                            val sideDataMap = sideData as? Map<Any?, *> ?: continue
                            val sideDataType = sideDataMap["side_data_type"] as? String
                            if (sideDataType == "Display Matrix") {
                                val rotationValue = sideDataMap["rotation"]
                                val rotation =
                                    when (rotationValue) {
                                        is Number -> rotationValue.toInt()
                                        is String -> rotationValue.toDoubleOrNull()?.toInt()
                                            ?: 0
                                        else -> 0
                                    }
                                if (rotation in -360..360) {
                                    return@withContext rotation
                                }
                            }
                        }
                    }
                }

                0
            } catch (e: Exception) {
                println("Docs: Error getting rotation from file: ${e.message}")
                0
            }
        }

    actual suspend fun compressVideo(inputPath: String, onProgress: ((Float) -> Unit)?): String? =
        withContext(Dispatchers.IO) {
            val fileManager = NSFileManager.defaultManager

            // Validate input file exists
            if (!fileManager.fileExistsAtPath(inputPath)) {
                println("Docs: Input file not found: $inputPath")
                return@withContext null
            }

            val cacheDir = getCacheDirectory()
            val outputPath = "$cacheDir/compressed_${getUniqueId(inputPath)}.mp4"

            // Remove existing file if any
            if (fileManager.fileExistsAtPath(outputPath)) {
                fileManager.removeItemAtPath(outputPath, null)
            }

            // Use libx264 with same settings as Android/Desktop for consistency
            // Note: FFmpegKit parses the command string, so we avoid single quotes in filters
            val command =
                "-y -i \"$inputPath\" -c:v libx264 -b:v 3000k -vf scale=min(1280\\,iw):-2 -preset fast \"$outputPath\""

            val session = FFmpegKit.execute(command)

            if (ReturnCode.isSuccess(session?.getReturnCode())) {
                outputPath
            } else {
                println("Docs: Error compressing video: ${session?.getFailStackTrace()}")
                null
            }
        }

    actual suspend fun segmentVideo(inputPath: String): Pair<String, String>? =
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

            val session = FFmpegKit.execute(command)

            if (ReturnCode.isSuccess(session?.getReturnCode())) {
                Pair(indexPath, segmentPath)
            } else {
                println("Docs: Error segmenting video: ${session?.getFailStackTrace()}")
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
        keyHeader: KeyHeader
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
                """
            $baseCommand
            -hls_time 6
            -hls_list_size 0
            -hls_flags single_file
            -hls_key_info_file "$keyInfoFilePath"
            -f hls
            -hls_segment_filename "$segmentPath"
            "$indexPath"
            """.trimIndent()

            val session = FFmpegKit.execute(command)

            if (ReturnCode.isSuccess(session?.getReturnCode())) {
                Pair(indexPath, segmentPath)
            } else {
                println("Docs: Error segment+encrypt video: ${session?.getFailStackTrace()}")
                null
            }
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
        val ivHex = iv.joinToString("") { "%02x".format(it) }

        // --- write key info file ---
        val keyInfoFilePath = "$outputDir/$keyInfoFileName"
        val keyInfoContents = """
        $keyFileName
        $keyFilePath
        $ivHex
    """.trimIndent()

        keyInfoContents.writeToFile(
            keyInfoFilePath,
            true,
            NSUTF8StringEncoding,
            null
        )

        return keyInfoFilePath
    }
}
