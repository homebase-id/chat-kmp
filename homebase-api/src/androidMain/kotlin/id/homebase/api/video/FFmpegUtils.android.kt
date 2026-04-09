package id.homebase.api.video

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import id.homebase.api.ActivityProvider
import id.homebase.api.client.KeyHeader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.media.MediaMetadataRetriever

actual object FFmpegUtils {
    private const val TAG = "FFmpegUtils"

    actual suspend fun getDurationMs(inputPath: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputPath)
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
        val file = File(filePath)
        // Simple deterministic ID generation based on file props
        return UUID.nameUUIDFromBytes("${file.name}_${file.length()}".toByteArray()).toString()
    }

    actual suspend fun grabThumbnail(inputPath: String): String? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireActivity().applicationContext
            val file = File(inputPath)
            if (!file.exists()) return@withContext null

            val uniqueId = getUniqueId(inputPath)
            val outputDir = context.cacheDir
            // thumb0001-ID.png
            val outputName = "thumb0001-$uniqueId.png"
            val outputPath = File(outputDir, outputName).absolutePath

            if (File(outputPath).exists()) return@withContext outputPath

            val commandDestination = File(outputDir, "thumb%04d-$uniqueId.png").absolutePath

            // -frames:v 1
            val command = "-y -i \"$inputPath\" -frames:v 1 \"$commandDestination\""

            Log.d(TAG, "Thumbnail command: $command")

            val session = FFmpegKit.execute(command)
            if (ReturnCode.isSuccess(session.returnCode)) {
                // Validate file creation
                if (File(outputPath).exists()) {
                    return@withContext outputPath
                } else {
                    Log.w(TAG, "Thumbnail generated success but file not found at $outputPath")
                    return@withContext null
                }
            } else {
                Log.e(TAG, "Thumbnail failed: ${session.failStackTrace}")
                return@withContext null
            }
        }

    actual suspend fun getRotationFromFile(filePath: String): Int =
        withContext(Dispatchers.IO) {
            try {
                val session =
                    FFprobeKit.execute(
                        "-v quiet -select_streams v:0 -show_entries side_data_list=side_data_type,rotation -of json=compact=1 \"$filePath\""
                    )
                val output = session.output
                if (output.isNullOrBlank()) return@withContext 0

                val json = JSONObject(output)
                val streams = json.optJSONArray("streams")
                val sideDataList =
                    streams?.optJSONObject(0)?.optJSONArray("side_data_list")
                        ?: json.optJSONArray("side_data_list")

                if (sideDataList != null) {
                    for (i in 0 until sideDataList.length()) {
                        val entry = sideDataList.optJSONObject(i)
                        if (entry?.optString("side_data_type") == "Display Matrix") {
                            val rotationStr = entry.optString("rotation")
                            val rotation = rotationStr.toDoubleOrNull()?.toInt() ?: 0
                            return@withContext if (rotation in -360..360) rotation else 0
                        }
                    }
                }
                return@withContext 0
            } catch (e: Exception) {
                Log.w(TAG, "getRotationFromFile failed", e)
                return@withContext 0
            }
        }

    private const val MAX_BITRATE = 3_000_000L
    private const val MAX_WIDTH = 1280

    actual suspend fun compressVideo(inputPath: String, onProgress: ((Float) -> Unit)?): String? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireActivity().applicationContext
            val file = File(inputPath)
            if (!file.exists()) {
                Log.e(TAG, "File not found: $inputPath")
                return@withContext null
            }

            // Check if compression is needed via ffprobe
            val needsCompression = run {
                try {
                    val session = FFprobeKit.getMediaInformation(inputPath)
                    val info = session?.mediaInformation ?: return@run true
                    val streams = info.streams ?: return@run true
                    val videoStream = streams.firstOrNull { it.type == "video" }
                        ?: return@run true

                    val codec = videoStream.codec?.lowercase()
                    val bitrate = videoStream.bitrate?.toLongOrNull()
                    val width = videoStream.width?.toInt()

                    val isH264 = codec == "h264"
                    val bitrateOk = bitrate != null && bitrate <= MAX_BITRATE
                    val widthOk = width != null && width <= MAX_WIDTH

                    Log.d(TAG, "Video info: codec=$codec, bitrate=$bitrate, width=$width, needsCompression=${!(isH264 && bitrateOk && widthOk)}")
                    !(isH264 && bitrateOk && widthOk)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to probe video, assuming compression needed", e)
                    true
                }
            }

            if (!needsCompression) {
                Log.d(TAG, "Video already optimal — skipping compression")
                return@withContext null
            }

            val outputDir = context.cacheDir
            val outputPath = File(outputDir, "compressed_${file.name}").absolutePath

            // Try hardware encoder first, fall back to software
            val hwArguments = mutableListOf(
                "-y", "-i", inputPath,
                "-c:v", "h264_mediacodec",
                "-b:v", "3000k",
                "-vf", "scale='min(1280,iw)':-2",
                outputPath
            )

            Log.d(TAG, "Compression with hardware encoder: $hwArguments")
            val hwSession = FFmpegKit.executeWithArguments(hwArguments.toTypedArray())

            if (ReturnCode.isSuccess(hwSession.returnCode)) {
                return@withContext outputPath
            }

            // Fall back to software encoder
            Log.w(TAG, "Hardware encoder failed, falling back to libx264")
            val swArguments = mutableListOf(
                "-y", "-i", inputPath,
                "-c:v", "libx264",
                "-b:v", "3000k",
                "-vf", "scale='min(1280,iw)':-2",
                "-preset", "fast",
                outputPath
            )

            val swSession = FFmpegKit.executeWithArguments(swArguments.toTypedArray())
            if (ReturnCode.isSuccess(swSession.returnCode)) {
                return@withContext outputPath
            } else {
                Log.e(TAG, "Compression failed: ${swSession.failStackTrace}")
                return@withContext null
            }
        }

    actual suspend fun segmentVideo(inputPath: String,
                                    onProgress: ((Float) -> Unit)?): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireActivity().applicationContext
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
                return@withContext null
            }
        }

    actual suspend fun cacheInputVideo(fileName: String, data: ByteArray): String =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireActivity().applicationContext
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
            val context = ActivityProvider.requireActivity().applicationContext
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

            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            if (ReturnCode.isSuccess(session.returnCode)) {
                Pair(playlistPath, segmentPath)
            } else {
                Log.e(TAG, "Segment+Encrypt failed: ${session.failStackTrace}")
                null
            }
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
