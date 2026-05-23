package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import java.io.BufferedReader
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual object FFmpegUtils {

    @Volatile private var cachedFfmpegVersion: String? = null
    @Volatile private var ffmpegVersionProbed: Boolean = false

    actual suspend fun getFfmpegVersion(): String? = withContext(Dispatchers.IO) {
        if (ffmpegVersionProbed) return@withContext cachedFfmpegVersion
        val v = if (!FFmpegBinaryManager.isAvailable()) {
            null
        } else {
            runCatching {
                val output = runProcessWithOutput(
                    listOf(FFmpegBinaryManager.ffmpegPath(), "-version")
                )
                parseFfmpegVersionBanner(output)
            }.getOrNull()
        }
        cachedFfmpegVersion = v
        ffmpegVersionProbed = true
        v
    }

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

    actual suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
    ): String? = withContext(Dispatchers.IO) {
        if (!FFmpegBinaryManager.isAvailable()) return@withContext null

        val inputFile = File(inputPath)
        if (!inputFile.exists()) return@withContext null

        val effectiveTrimStart = if (trimStartMs != null && trimEndMs != null) trimStartMs else null
        val effectiveTrimEnd = if (trimStartMs != null && trimEndMs != null) trimEndMs else null

        val outputPath =
            "${System.getProperty("java.io.tmpdir")}/compressed_${inputFile.name}"
        val sourceDurationMs = getDurationMs(inputPath)
        val inputBytes = inputFile.length()
        val probe = probeVideoTrackViaFfprobe(inputPath)
        // ffprobe reports raw container dims; rotation rides on the side-data
        // displaymatrix. Planner needs both so portrait phone captures don't
        // get a landscape scale filter and end up squished.
        val rotation = getRotationFromFile(inputPath)
        val effectiveDurationMs = if (effectiveTrimEnd != null && effectiveTrimStart != null) {
            (effectiveTrimEnd - effectiveTrimStart).coerceAtLeast(1L)
        } else {
            sourceDurationMs
        }

        // Hand probed input + caller params to the commonMain planner.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = inputPath,
            outputPath = outputPath,
            quality = quality,
            trimStartMs = effectiveTrimStart,
            trimEndMs = effectiveTrimEnd,
            probedWidthPx = probe.widthPx,
            probedHeightPx = probe.heightPx,
            probedCodecMime = probe.codec,
            inputDurationMs = sourceDurationMs,
            inputBytes = inputBytes,
            rotationDegrees = rotation,
        )

        if (plan.skipReason != null) {
            println("compressVideo: AlreadyOptimal — ${plan.skipReason}")
            return@withContext null
        }

        // Planner emits "argv after ffmpeg" — Desktop's ProcessBuilder needs
        // the binary path prepended. We also append the JVM-specific progress-
        // wiring flags so `runProcessWithLogs` can parse `-progress pipe:1`.
        val command = buildList {
            add(FFmpegBinaryManager.ffmpegPath())
            addAll(plan.args)
            add("-progress"); add("pipe:1")
            add("-nostats")
        }

        val result = runProcessWithLogs(
            command = command,
            totalDurationMs = effectiveDurationMs,
            onProgress = onProgress,
        )

        if (result.exitCode == 0 && File(outputPath).exists()) {
            outputPath
        } else {
            // FFmpeg failed — delete the partial/empty output (see #5).
            deleteFailedFfmpegOutput(outputPath)
            null
        }
    }

    /**
     * Probes (codec, width, height) of the first video track via `ffprobe`.
     * Returns null codec / zero dims on any probe failure — caller falls back
     * to "no -vf scale" and the already-optimal predicate fails through to a
     * real transcode.
     */
    private data class FfprobeResult(val codec: String?, val widthPx: Int, val heightPx: Int)

    private suspend fun probeVideoTrackViaFfprobe(inputPath: String): FfprobeResult {
        val probeCommand = listOf(
            FFmpegBinaryManager.ffprobePath(),
            "-v", "quiet",
            "-select_streams", "v:0",
            "-show_entries", "stream=codec_name,width,height",
            "-of", "csv=p=0",
            inputPath,
        )
        val output = runProcessWithOutput(probeCommand).trim()
        val parts = output.split(",")
        if (parts.size < 3) return FfprobeResult(null, 0, 0)
        return FfprobeResult(
            codec = parts[0].lowercase().ifBlank { null },
            widthPx = parts[1].toIntOrNull() ?: 0,
            heightPx = parts[2].toIntOrNull() ?: 0,
        )
    }

    private fun formatSeconds(ms: Long): String {
        // Locale-safe (avoid comma decimal separator in some default locales).
        val whole = ms / 1000
        val frac = ms % 1000
        return "$whole.${frac.toString().padStart(3, '0')}"
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

            try {
                segmentInternal(
                    inputPath = inputPath,
                    outputDir = outputDir,
                    onProgress = onProgress
                )
            } catch (e: Throwable) {
                // segmentInternal throws on FFmpeg failure — delete the leftover
                // hls_<uuid>/ dir before the exception propagates (see #5).
                deleteFailedFfmpegOutput(outputDir.absolutePath)
                throw e
            }
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

            try {
                val keyInfoFile = generateHlsKeyInfoFile(
                    outputDir = outputDir,
                    aesKey = keyHeader.aesKey.unsafeBytes,
                    iv = keyHeader.iv
                )

                val result = segmentInternal(
                    inputPath = inputPath,
                    outputDir = outputDir,
                    keyInfoFile = keyInfoFile,
                    onProgress = onProgress
                )
                // Segmentation done — FFmpeg has consumed the key material. Delete it now
                // so the plaintext AES key doesn't linger in the temp dir (see #7).
                // segmentInternal throws on failure, so reaching here means success.
                deleteHlsKeyMaterial(outputDir.absolutePath)
                result
            } catch (e: Throwable) {
                // segmentInternal throws on FFmpeg failure — delete the leftover
                // hls_<uuid>/ dir (key material included) before rethrowing (see #5).
                deleteFailedFfmpegOutput(outputDir.absolutePath)
                throw e
            }
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