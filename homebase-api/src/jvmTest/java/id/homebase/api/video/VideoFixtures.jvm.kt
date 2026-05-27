package id.homebase.api.video

import java.io.File

internal actual suspend fun stageSampleVideoForFfmpegTest(): String? {
    if (!FFmpegBinaryManager.isAvailable()) return null
    val bytes = VideoFixtures::class.java.getResourceAsStream("/test_videos/sample.mp4")
        ?.readBytes() ?: return null
    val temp = File.createTempFile("vidfixture_", "_sample.mp4")
    temp.writeBytes(bytes)
    return temp.absolutePath
}

internal actual suspend fun cleanupStagedSampleVideo(path: String) {
    runCatching { File(path).delete() }
}

private object VideoFixtures
