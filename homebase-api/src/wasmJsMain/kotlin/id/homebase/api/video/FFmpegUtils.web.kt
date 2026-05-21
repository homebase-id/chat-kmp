package id.homebase.api.video

import id.homebase.api.client.KeyHeader

// Pre-flight stub. Video processing on the web will require a separate
// strategy (ffmpeg.wasm, MediaRecorder, or server-side transcoding) — out
// of scope for the pre-flight pass.
actual object FFmpegUtils {
    actual fun getUniqueId(filePath: String): String = filePath

    actual suspend fun grabThumbnail(inputPath: String): String? = null

    actual suspend fun getRotationFromFile(filePath: String): Int = 0

    actual suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
    ): String? = null

    actual suspend fun getDurationMs(inputPath: String): Long = 0L

    // ┌─────────────────────────────────────────────────────────────────────────┐
    // │ ⚠️  WASM TODO — DO NOT FORGET WHEN IMPLEMENTING VIDEO SEGMENTATION       │
    // │                                                                         │
    // │ When this stub becomes a real implementation, it MUST delete the HLS    │
    // │ key material the moment segmentation finishes — exactly as the Android, │
    // │ JVM and native actuals now do (see #7).                                 │
    // │                                                                         │
    // │ HLS encryption writes the raw AES key (`enc.key`) + `keyinfo.txt` into  │
    // │ the output dir because FFmpeg needs them ON DISK while it encrypts each │
    // │ segment. Once segmentation is done they are dead weight — the key is    │
    // │ NOT needed for transmission (it travels in the message KeyHeader), and  │
    // │ leaving a plaintext AES key in the cache directory is a security leak.  │
    // │                                                                         │
    // │ On a successful segmentation, call:                                     │
    // │     deleteHlsKeyMaterial(outputDir)                                     │
    // │ from id.homebase.api.video.HlsKeyMaterial (commonMain) — it removes      │
    // │ HLS_KEY_FILE_NAME + HLS_KEY_INFO_FILE_NAME and is already cross-platform.│
    // │ Note it relies on okio's systemFileSystem; the wasmJs systemFileSystem  │
    // │ is currently an in-memory FakeFileSystem, so confirm the segmentation   │
    // │ strategy (ffmpeg.wasm / MediaRecorder / server-side) writes through the │
    // │ same filesystem the deletion reads, or the call will be a silent no-op. │
    // └─────────────────────────────────────────────────────────────────────────┘
    actual suspend fun segmentAndEncryptVideo(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: ((Float) -> Unit)?,
    ): Pair<String, String>? = null

    actual suspend fun segmentVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Pair<String, String>? = null

    actual suspend fun cacheInputVideo(fileName: String, data: ByteArray): String = fileName

    actual suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean = false
}
