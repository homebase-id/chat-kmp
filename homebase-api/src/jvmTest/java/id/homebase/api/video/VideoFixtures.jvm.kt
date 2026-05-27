package id.homebase.api.video

import java.io.File

internal actual suspend fun stageSampleVideoForFfmpegTest(): String? {
    if (!FFmpegBinaryManager.isAvailable()) return null
    val temp = File.createTempFile("vidfixture_", "_sample.mp4")
    temp.writeBytes(SampleVideoFixture.bytes)
    return temp.absolutePath
}

internal actual suspend fun cleanupStagedSampleVideo(path: String) {
    runCatching { File(path).delete() }
}
