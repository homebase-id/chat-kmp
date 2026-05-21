package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.file.systemFileSystem
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * File names FFmpeg's HLS encryption needs on disk during segmentation: the raw
 * AES key and the keyinfo file that points FFmpeg at it. Each platform's
 * `FFmpegUtils.generateHlsKeyInfoFile` writes exactly these names — keep this the
 * single source of truth so [deleteHlsKeyMaterial] removes exactly what was written.
 */
const val HLS_KEY_FILE_NAME: String = "enc.key"
const val HLS_KEY_INFO_FILE_NAME: String = "keyinfo.txt"

/**
 * Deletes the HLS key material ([HLS_KEY_FILE_NAME] and [HLS_KEY_INFO_FILE_NAME])
 * from [outputDirPath].
 *
 * FFmpeg needs the raw AES key on disk *during* segmentation — it reads the
 * keyinfo file to encrypt each segment — but the moment segmentation finishes the
 * key is dead weight: it is not needed for transmission (the key travels in the
 * message [id.homebase.api.client.KeyHeader], not the file), and leaving a
 * plaintext AES key sitting in the cache directory is a needless exposure. Call
 * this as soon as `segmentAndEncryptVideo` finishes, before returning.
 *
 * Best-effort: a missing file or a delete failure is logged and skipped, never
 * thrown — tidy-up must not fail an otherwise-successful segmentation.
 *
 * @return how many key-material files were actually deleted (0..2).
 */
fun deleteHlsKeyMaterial(
    outputDirPath: String,
    fileSystem: FileSystem = systemFileSystem,
): Int {
    val dir = outputDirPath.toPath()
    var deleted = 0
    for (name in arrayOf(HLS_KEY_FILE_NAME, HLS_KEY_INFO_FILE_NAME)) {
        val path = dir / name
        try {
            if (fileSystem.exists(path)) {
                fileSystem.delete(path)
                deleted++
            }
        } catch (e: Exception) {
            Logger.w(tag = "FFmpegUtils", throwable = e) {
                "failed to delete HLS key material $name in $outputDirPath"
            }
        }
    }
    return deleted
}
