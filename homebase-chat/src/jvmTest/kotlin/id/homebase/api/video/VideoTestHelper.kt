package id.homebase.api.video

import java.io.File

/**
 * Shared test utilities for video tests. Mirrors `ImageTestHelper`.
 *
 * The fixture under `jvmTest/resources/test_videos/sample.mp4` is a 6-second
 * 320x180 H.264 clip (~26 KB). To regenerate from a source video:
 *
 *   ffmpeg -y -i source.mp4 \
 *          -t 6 -vf scale=320:-2 -c:v libx264 -preset slow -crf 30 \
 *          -c:a aac -b:a 32k -movflags +faststart \
 *          homebase-api/src/jvmTest/resources/test_videos/sample.mp4
 */
object VideoTestHelper {

    /** Read a fixture as raw bytes. */
    fun loadVideo(name: String): ByteArray =
        this::class.java.getResourceAsStream("/test_videos/$name")?.readBytes()
            ?: error("Test video not found: $name")

    /**
     * Copy a fixture to a temp file and return the absolute path.
     * `FFmpegUtils.compressVideo` and friends require a filesystem path,
     * not a stream.
     *
     * Caller is responsible for deleting the file in a `finally` block.
     */
    fun copyToTempFile(name: String): String {
        val bytes = loadVideo(name)
        val temp = File.createTempFile("vidfixture_", "_$name")
        temp.writeBytes(bytes)
        return temp.absolutePath
    }
}
