package id.homebase.chat.conversationlist

import io.github.vinceglb.filekit.PlatformFile

// Audio recording is not supported on web yet — the browser has no filesystem-path
// PlatformFile and no MediaRecorder wiring here. These actuals exist only so the shared
// AttachmentHandler compiles for wasmJs; the recording action arms are never reached on web.

actual fun newRecordingFile(fileName: String): PlatformFile =
    error("Audio recording is not supported on web")

actual fun newWaveformCacheFile(fileName: String): PlatformFile =
    error("Audio recording is not supported on web")

actual suspend fun PlatformFile.writeBytesCompat(bytes: ByteArray) {
    error("Audio recording is not supported on web")
}

actual suspend fun PlatformFile.deleteCompat(mustExist: Boolean) {
    // no-op on web
}
