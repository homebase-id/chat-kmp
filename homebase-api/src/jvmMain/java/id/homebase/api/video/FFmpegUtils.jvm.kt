package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import java.io.BufferedReader
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual object FFmpegUtils {

    actual suspend fun getDurationMs(inputPath: String): Long {
        val command = listOf(
            FFmpegBinaryManager.ffprobePath(),
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            inputPath
        )

        val output = runProcessWithOutput(command)
        return (output.trim().toDoubleOrNull() ?: 0.0).times(1000).toLong()
    }

    actual fun getUniqueId(filePath: String): String {
        val file = File(filePath)
        return UUID.nameUUIDFromBytes("${file.name}_${file.length()}".toByteArray()).toString()
    }

    actual suspend fun grabThumbnail(inputPath: String): String? =
        withContext(Dispatchers.IO) {
            if (!FFmpegBinaryManager.isAvailable()) {
                println("FFmpeg binaries not available for this platform")
                return@withContext null
            }

            val uniqueId = getUniqueId(inputPath)
            val outputPath = "${System.getProperty("java.io.tmpdir")}/thumb_$uniqueId.jpg"

            val command =
                listOf(
                    FFmpegBinaryManager.ffmpegPath(),
                    "-y",
                    "-i",
                    inputPath,
                    "-ss",
                    "00:00:01.000",
                    "-vframes",
                    "1",
                    outputPath
                )

            val exitCode = runProcess(command)
            if (exitCode == 0 && File(outputPath).exists()) {
                outputPath
            } else {
                null
            }
        }

    actual suspend fun getRotationFromFile(filePath: String): Int =
        withContext(Dispatchers.IO) {
            if (!FFmpegBinaryManager.isAvailable()) {
                return@withContext 0
            }

            val command =
                listOf(
                    FFmpegBinaryManager.ffprobePath(),
                    "-v",
                    "quiet",
                    "-select_streams",
                    "v:0",
                    "-show_entries",
                    "stream_side_data=rotation",
                    "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    filePath
                )

            val output = runProcessWithOutput(command)
            output.trim().toIntOrNull() ?: 0
        }

    private const val MAX_BITRATE = 3_000_000L
    private const val MAX_WIDTH = 1280

    actual suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?
    ): String? =
        withContext(Dispatchers.IO) {
            if (!FFmpegBinaryManager.isAvailable()) return@withContext null

            // Check if compression is needed via ffprobe
            val needsCompression = run {
                val probeCommand = listOf(
                    FFmpegBinaryManager.ffprobePath(),
                    "-v", "quiet",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=codec_name,bit_rate,width",
                    "-of", "csv=p=0",
                    inputPath
                )
                val output = runProcessWithOutput(probeCommand)
                val parts = output.trim().split(",")
                if (parts.size >= 3) {
                    val codec = parts[0].lowercase()
                    val bitrate = parts[1].toLongOrNull()
                    val width = parts[2].toIntOrNull()
                    val isH264 = codec == "h264"
                    val bitrateOk = bitrate != null && bitrate <= MAX_BITRATE
                    val widthOk = width != null && width <= MAX_WIDTH
                    !(isH264 && bitrateOk && widthOk)
                } else {
                    true // assume compression needed if probe fails
                }
            }

            if (!needsCompression) {
                println("Video already optimal — skipping compression")
                return@withContext null
            }

            val inputFile = File(inputPath)
            val outputPath =
                "${System.getProperty("java.io.tmpdir")}/compressed_${inputFile.name}"

            val durationMs = getDurationMs(inputPath)

            val command = listOf(
                FFmpegBinaryManager.ffmpegPath(),
                "-y",
                "-i", inputPath,
                "-c:v", "libx264",
                "-b:v", "3000k",
                "-vf", "scale='min(1280,iw)':-2",
                "-preset", "fast",
                "-progress", "pipe:1",
                "-nostats",
                outputPath
            )

            val result = runProcessWithLogs(
                command = command,
                totalDurationMs = durationMs,
                onProgress = onProgress
            )

            if (result.exitCode == 0 && File(outputPath).exists()) {
                outputPath
            } else {
                null
            }
        }


    actual suspend fun segmentVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?
    ): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            if (!FFmpegBinaryManager.isAvailable()) {
                println("FFmpeg binaries not available for this platform")
                return@withContext null
            }

            val outputDir = File(
                System.getProperty("java.io.tmpdir"),
                "hls_${UUID.randomUUID()}"
            ).apply { mkdirs() }

            segmentInternal(
                inputPath = inputPath,
                outputDir = outputDir,
                onProgress = onProgress
            )
        }

    actual suspend fun segmentAndEncryptVideo(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: ((Float) -> Unit)?
    ): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            if (!FFmpegBinaryManager.isAvailable()) {
                println("FFmpeg binaries not available for this platform")
                throw VideoSegmentException(
                    message = "Binaries not found",
                    command = emptyList(),
                    exitCode = 1,
                    ffmpegOutput = ""
                )
            }

            val outputDir = File(
                System.getProperty("java.io.tmpdir"),
                "hls_${UUID.randomUUID()}"
            ).apply { mkdirs() }

            val keyInfoFile = generateHlsKeyInfoFile(
                outputDir = outputDir,
                aesKey = keyHeader.aesKey.unsafeBytes,
                iv = keyHeader.iv
            )

            segmentInternal(
                inputPath = inputPath,
                outputDir = outputDir,
                keyInfoFile = keyInfoFile,
                onProgress = onProgress
            )
        }

    private suspend fun segmentInternal(
        inputPath: String,
        outputDir: File,
        keyInfoFile: File? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Pair<String, String>? {
        val playlistPath = File(outputDir, "index.m3u8").absolutePath
        val segmentPath = File(outputDir, "index.ts").absolutePath

        val rotation = getRotationFromFile(inputPath)
        val absRot = kotlin.math.abs(((rotation % 360) + 360) % 360)
        val needsRotationFix = absRot == 90 || absRot == 270

        val command = mutableListOf<String>().apply {
            add(FFmpegBinaryManager.ffmpegPath())
            add("-y")
            add("-i")
            add(inputPath)

            if (!needsRotationFix) {
                addAll(listOf("-codec:v", "copy", "-codec:a", "copy"))
            } else {
                addAll(
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

            addAll(
                listOf(
                    "-hls_time", "6",
                    "-hls_list_size", "0",
                    "-hls_flags", "single_file"
                )
            )

            // 🔐 Optional encryption
            if (keyInfoFile != null) {
                add("-hls_key_info_file")
                add(keyInfoFile.absolutePath)
            }

            add("-progress")
            add("pipe:1")
            add("-nostats")

            addAll(
                listOf(

                    "-f", "hls",
                    "-hls_segment_filename", segmentPath,
                    playlistPath
                )
            )
        }

        val durationMs = getDurationMs(inputPath)

        val result = runProcessWithLogs(
            command = command,
            totalDurationMs = durationMs,
            onProgress = onProgress
        )

        if (result.exitCode != 0) {
            throw VideoSegmentException(
                message = "FFmpeg failed during segment${if (keyInfoFile != null) "+encrypt" else ""}",
                command = command,
                exitCode = result.exitCode,
                ffmpegOutput = result.output
            )
        }

        if (!File(playlistPath).exists()) {
            throw VideoSegmentException(
                message = "FFmpeg reported success but index.m3u8 was not created",
                command = command,
                exitCode = result.exitCode,
                ffmpegOutput = result.output
            )
        }

        return playlistPath to segmentPath
    }


    actual suspend fun cacheInputVideo(fileName: String, data: ByteArray): String =
        withContext(Dispatchers.IO) {
            val cacheFile = File(System.getProperty("java.io.tmpdir"), "input_$fileName")
            cacheFile.writeBytes(data)
            cacheFile.absolutePath
        }

    private fun runProcess(command: List<String>): Int {
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)

        println("Running: ${command.joinToString(" ")}")

        val process = processBuilder.start()

        // Consume output to prevent blocking
        process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line -> println("[FFmpeg] $line") }
        }

        val completed = process.waitFor(5, TimeUnit.MINUTES)
        return if (completed) process.exitValue() else -1
    }

    private fun runProcessWithOutput(command: List<String>): String {
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)

        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)

        process.waitFor(30, TimeUnit.SECONDS)
        return output
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

        // 3️⃣ Write key info file (for FFmpeg)
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

    private fun runProcessWithLogs(
        command: List<String>,
        totalDurationMs: Long,
        onProgress: ((Float) -> Unit)?
    ): ProcessResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()

        val readerThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    output.appendLine(line)

                    if (onProgress != null && line.startsWith("out_time_ms=")) {
                        val outMs =
                            line.removePrefix("out_time_ms=").toLongOrNull() ?: return@forEach
                        val pct = (outMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                        onProgress(pct)
                    }
                }
            }
        }

        readerThread.start()
        process.waitFor(5, TimeUnit.MINUTES)
        readerThread.join()

        return ProcessResult(
            exitCode = process.exitValue(),
            output = output.toString()
        )
    }

    actual suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean {
        // Desktop HLS→MP4 export not implemented yet (VLC-J, no bundled FFmpeg).
        return false
    }
}

data class ProcessResult(
    val exitCode: Int,
    val output: String
)