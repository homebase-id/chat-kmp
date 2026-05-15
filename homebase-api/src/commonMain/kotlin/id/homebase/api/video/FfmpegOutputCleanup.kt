package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.file.safeDeleteRecursively
import id.homebase.api.file.systemFileSystem
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Best-effort delete of FFmpeg output left behind by a *failed* compress or
 * segment operation.
 *
 * `compressVideo` / `segmentVideo` / `segmentAndEncryptVideo` each write their
 * output into the cache directory and, on FFmpeg failure, used to just return
 * null (or throw) — leaving a partial or empty file (`compressed_*.mp4`,
 * `ffmpeg-segmented-*.{m3u8,ts}`) or a whole `hls_<uuid>/` directory behind,
 * uncapped and never evicted. Each failure path now calls this to delete what
 * it created.
 *
 * [path] may be a single file or a directory; directories are removed
 * recursively. Never throws — a cleanup failure must not mask the original
 * FFmpeg failure the caller is already handling.
 *
 * @return true if something was deleted; false if [path] did not exist or
 *   could not be removed.
 */
fun deleteFailedFfmpegOutput(
    path: String,
    fileSystem: FileSystem = systemFileSystem,
): Boolean {
    // Guard against a catastrophic delete. This only ever runs on a leftover
    // FFmpeg output path — a `compressed_*.mp4` file or an `hls_<uuid>/` dir
    // under the cache directory — so real callers always pass a deep absolute
    // path. A blank, relative, or filesystem-root path here means an upstream
    // bug; refuse it rather than risk deleteRecursively on the wrong tree.
    if (path.isBlank()) {
        Logger.w(tag = "FFmpegUtils") { "refusing to clean up a blank FFmpeg output path" }
        return false
    }
    val p = path.toPath()
    if (!p.isAbsolute || p.parent == null) {
        Logger.w(tag = "FFmpegUtils") {
            "refusing to delete unsafe FFmpeg output path '$path' — " +
                "expected an absolute path with a parent"
        }
        return false
    }
    return try {
        if (!fileSystem.exists(p)) return false
        if (fileSystem.metadata(p).isDirectory) {
            // Route folder deletes through the centralized safety check; the
            // guard above already ensured p is absolute with a parent.
            val parent = p.parent ?: return false
            safeDeleteRecursively(parent.toString(), p.name, fileSystem)
        } else {
            fileSystem.delete(p)
            true
        }
    } catch (e: Exception) {
        Logger.w(tag = "FFmpegUtils", throwable = e) {
            "failed to clean up FFmpeg output at $path"
        }
        false
    }
}
