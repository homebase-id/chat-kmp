package id.homebase.api.image

import co.touchlab.kermit.Logger

/**
 * Desktop/JVM: Convert HEIC to JPEG using the bundled FFmpeg binary.
 *
 * The rest of the image pipeline (the [ImageUtils] object and [toImageBitmap]) is shared
 * across Desktop/iOS/Web in the `skiaMain` source set — only this HEIC bridge is per-platform.
 */
actual fun convertHeicToJpeg(heicBytes: ByteArray): ByteArray? {
    return try {
        if (!id.homebase.api.video.FFmpegBinaryManager.isAvailable()) {
            Logger.w(tag = "convertHeicToJpeg") { "FFmpeg not available, cannot convert HEIC" }
            return null
        }
        val tmpDir = System.getProperty("java.io.tmpdir")
        val inputFile = java.io.File(tmpDir, "heic_input_${System.nanoTime()}.heic")
        val outputFile = java.io.File(tmpDir, "heic_output_${System.nanoTime()}.jpg")
        try {
            inputFile.writeBytes(heicBytes)
            val command = listOf(
                id.homebase.api.video.FFmpegBinaryManager.ffmpegPath(),
                "-y", "-i", inputFile.absolutePath,
                "-q:v", "2",
                outputFile.absolutePath
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
            val completed = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (completed && process.exitValue() == 0 && outputFile.exists()) {
                outputFile.readBytes()
            } else {
                Logger.w(tag = "convertHeicToJpeg") { "FFmpeg HEIC conversion failed (exit=${if (completed) process.exitValue() else -1})" }
                null
            }
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = "convertHeicToJpeg") { "Desktop HEIC conversion failed" }
        null
    }
}
