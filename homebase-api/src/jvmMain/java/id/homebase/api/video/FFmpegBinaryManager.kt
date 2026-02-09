package id.homebase.api.video

import java.io.File
import java.io.InputStream
import kotlin.io.copyTo
import kotlin.io.outputStream
import kotlin.io.use
import kotlin.jvm.java
import kotlin.text.contains
import kotlin.text.lowercase
import kotlin.text.startsWith

/** Manages extraction and access to bundled FFmpeg binaries for the current platform. */
object FFmpegBinaryManager {
    private val tempDir: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "homebase-ffmpeg")
        dir.mkdirs()
        dir
    }

    private val platformKey: String by lazy {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        val osKey =
            when {
                os.contains("mac") || os.contains("darwin") -> "macos"
                os.contains("linux") -> "linux"
                os.contains("windows") -> "windows"
                else -> throw kotlin.UnsupportedOperationException("Unsupported OS: $os")
            }

        val archKey =
            when {
                arch == "aarch64" || arch == "arm64" -> "arm64"
                arch == "amd64" || arch == "x86_64" || arch.contains("64") -> "x64"
                else -> throw kotlin.UnsupportedOperationException("Unsupported architecture: $arch")
            }

        "$osKey-$archKey"
    }

    private val binaryExtension: String
        get() = if (platformKey.startsWith("windows")) ".exe" else ""

    /**
     * Returns the path to the extracted ffmpeg binary. Extracts from resources if not already
     * present.
     */
    fun ffmpegPath(): String = extractBinary("ffmpeg$binaryExtension").absolutePath

    /**
     * Returns the path to the extracted ffprobe binary. Extracts from resources if not already
     * present.
     */
    fun ffprobePath(): String = extractBinary("ffprobe$binaryExtension").absolutePath

    /** Checks if FFmpeg binaries are available for the current platform. */
    fun isAvailable(): Boolean {
        val resourcePath = "/ffmpeg/$platformKey/ffmpeg$binaryExtension"
        return FFmpegBinaryManager::class.java.getResourceAsStream(resourcePath) != null
    }

    private fun extractBinary(name: String): File {
        val outputFile = File(tempDir, "$platformKey-$name")

        if (outputFile.exists() && outputFile.canExecute()) {
            return outputFile
        }

        val resourcePath = "/ffmpeg/$platformKey/$name"
        val inputStream: InputStream =
                FFmpegBinaryManager::class.java.getResourceAsStream(resourcePath)
                        ?: throw kotlin.IllegalStateException(
                            "FFmpeg binary not found for platform $platformKey. " +
                                    "Expected resource at: $resourcePath. " +
                                    "Please add the binary to composeApp/src/desktopMain/resources/ffmpeg/$platformKey/"
                        )

        inputStream.use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        }

        // Set executable permission on Unix systems
        if (!platformKey.startsWith("windows")) {
            outputFile.setExecutable(true)
        }

        return outputFile
    }
}
