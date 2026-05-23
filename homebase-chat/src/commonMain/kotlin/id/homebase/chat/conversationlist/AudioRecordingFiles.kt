package id.homebase.chat.conversationlist

import io.github.vinceglb.filekit.PlatformFile

/**
 * Platform file helpers for the audio-recording flow. filekit's disk-backed
 * `FileKit.filesDir` / `FileKit.cacheDir` plus `write` / `delete` exist on android/jvm/apple
 * but not on wasmJs (the browser has no filesystem paths), so they're abstracted here. The
 * web actuals throw / no-op — audio recording is not supported on web yet.
 */

/** A `filesDir`-backed file for a new audio recording. */
expect fun newRecordingFile(fileName: String): PlatformFile

/** A `cacheDir`-backed file for a generated waveform image. */
expect fun newWaveformCacheFile(fileName: String): PlatformFile

/** Write [bytes] to this file. */
expect suspend fun PlatformFile.writeBytesCompat(bytes: ByteArray)

/** Delete this file; [mustExist] mirrors filekit's flag. */
expect suspend fun PlatformFile.deleteCompat(mustExist: Boolean)
