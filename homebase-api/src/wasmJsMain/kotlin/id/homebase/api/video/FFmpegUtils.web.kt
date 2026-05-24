package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.file.systemFileSystem
import okio.Path.Companion.toPath

/**
 * Web (wasmJs) video pipeline backed by ffmpeg.wasm (FFmpeg n5.1.4, single-thread
 * @ffmpeg/core) via the `globalThis.__odinFfmpeg` bridge ([FFmpegBridge]).
 *
 * Two filesystems are in play and they are SEPARATE:
 *  - okio [systemFileSystem] (an in-memory FakeFileSystem) — what the rest of the upload
 *    pipeline reads/writes via FileOperationsProvider. Input paths arrive as okio paths,
 *    and every function that produces output must return an okio path.
 *  - ffmpeg.wasm's in-worker MEMFS — where ffmpeg actually reads/writes. We bridge by
 *    reading input bytes from okio, [FFmpegBridge.writeFile] into MEMFS, exec, then
 *    [FFmpegBridge.readFile] the result back out and write it to a fresh okio path.
 *
 * Milestone 1 (this pass): compress (via the shared [FfmpegCompressPlanner]), thumbnail
 * and duration/rotation (browser-native via mp4box / <video>, no core load), version (lazy).
 * Milestone 2 (TODO below): HLS segment/encrypt + remux.
 */
actual object FFmpegUtils {

    private const val CACHE_DIR = "/tmp/homebase"

    actual fun getUniqueId(filePath: String): String = filePath

    actual suspend fun grabThumbnail(inputPath: String): String? {
        val safe = inputPath.substringAfterLast('/').ifBlank { "video" }
        val thumbPath = "$CACHE_DIR/thumb-$safe.jpg"
        val existing = thumbPath.toPath()
        if (systemFileSystem.exists(existing) &&
            (systemFileSystem.metadataOrNull(existing)?.size ?: 0L) > 0L
        ) {
            return thumbPath
        }
        val jpeg = VideoThumbnailExtractor.extractPosterFrame(inputPath) ?: return null
        return writeCacheBytes("thumb-$safe.jpg", jpeg)
    }

    actual suspend fun getRotationFromFile(filePath: String): Int {
        val bytes = readOkioBytes(filePath) ?: return 0
        return FFmpegBridge.probe(bytes)?.rotationDegrees ?: 0
    }

    actual suspend fun getDurationMs(inputPath: String): Long {
        val bytes = readOkioBytes(inputPath) ?: return 0L
        return FFmpegBridge.probe(bytes)?.durationMs ?: 0L
    }

    /**
     * Compress (+ optional trim) via ffmpeg.wasm, mirroring the native actuals: probe the
     * input, hand it to [FfmpegCompressPlanner], and either short-circuit (already-optimal /
     * small) by returning null — caller falls back to the original file — or run ffmpeg and
     * return the okio path of the compressed mp4.
     *
     * Small-video parity with native: the planner's already-optimal predicate needs the codec,
     * which the mp4box probe supplies, so an in-budget H.264 clip skips ffmpeg here exactly as
     * it does on Android/iOS/Desktop. (Web v1 does not strip location atoms on the skip path —
     * a minor known gap vs Android's Mp4LocationStripper.)
     */
    actual suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
    ): String? {
        val inputBytes = readOkioBytes(inputPath) ?: return null

        val hasTrim = trimStartMs != null && trimEndMs != null
        val effTrimStart = if (hasTrim) trimStartMs else null
        val effTrimEnd = if (hasTrim) trimEndMs else null

        val probe = FFmpegBridge.probe(inputBytes)
        val durationMs = probe?.durationMs ?: 0L

        // MEMFS-relative names so plan.args reference the in-worker files directly.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = MEMFS_INPUT,
            outputPath = MEMFS_OUTPUT,
            quality = quality,
            trimStartMs = effTrimStart,
            trimEndMs = effTrimEnd,
            probedWidthPx = probe?.widthPx ?: 0,
            probedHeightPx = probe?.heightPx ?: 0,
            probedCodecMime = probe?.codec, // null probe → no short-circuit → transcode
            inputDurationMs = durationMs,
            inputBytes = inputBytes.size.toLong(),
            rotationDegrees = probe?.rotationDegrees ?: 0,
            // libx264: the single-thread core has no hardware encoder.
        )

        if (plan.skipReason != null) {
            // Already-optimal / within envelope: skip ffmpeg, let the caller keep the original.
            return null
        }

        FFmpegBridge.writeFile(MEMFS_INPUT, inputBytes)
        val status = FFmpegBridge.exec(plan.args, onProgress)
        if (status != 0) {
            FFmpegBridge.deleteFile(MEMFS_INPUT)
            FFmpegBridge.deleteFile(MEMFS_OUTPUT)
            return null
        }
        val outBytes = FFmpegBridge.readFile(MEMFS_OUTPUT)
        FFmpegBridge.deleteFile(MEMFS_INPUT)
        FFmpegBridge.deleteFile(MEMFS_OUTPUT)

        return writeCacheBytes("compressed_${inputPath.substringAfterLast('/')}", outBytes)
    }

    actual suspend fun cacheInputVideo(fileName: String, data: ByteArray): String =
        writeCacheBytes("input_$fileName", data)

    // ────────────────────────────────────────────────────────────────────────────
    // Milestone 2 — HLS segmentation + remux. Still stubbed.
    //
    // When implemented, generate enc.key + keyinfo.txt INSIDE MEMFS only (never write the
    // AES key to okio), delete them from MEMFS the moment segmentation finishes, and read
    // out only the .m3u8 + .ts. deleteHlsKeyMaterial(okioDir) is a no-op on web because the
    // key never touches okio — the HLS key travels in the message KeyHeader.
    // ────────────────────────────────────────────────────────────────────────────

    actual suspend fun segmentAndEncryptVideo(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: ((Float) -> Unit)?,
    ): Pair<String, String>? = null

    actual suspend fun segmentVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Pair<String, String>? = null

    actual suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean = false

    private var cachedVersion: String? = null
    private var versionProbed = false

    /**
     * Lazy: only reads the version if the core is already loaded (a prior compress/segment),
     * so we never trigger a ~22 MB core download just to report a version string.
     */
    actual suspend fun getFfmpegVersion(): String? {
        if (versionProbed) return cachedVersion
        if (!FFmpegBridge.isCoreLoaded()) return null
        cachedVersion = parseFfmpegVersionBanner(FFmpegBridge.versionBannerIfLoaded())
        versionProbed = true
        return cachedVersion
    }

    // ---- okio helpers (the FakeFileSystem the upload pipeline reads back) ----

    private fun readOkioBytes(path: String): ByteArray? =
        runCatching { systemFileSystem.read(path.toPath()) { readByteArray() } }.getOrNull()

    private fun writeCacheBytes(name: String, bytes: ByteArray): String {
        val dir = CACHE_DIR.toPath()
        systemFileSystem.createDirectories(dir)
        val path = dir / name
        systemFileSystem.write(path) { write(bytes) }
        return path.toString()
    }

    private const val MEMFS_INPUT = "input.mp4"
    private const val MEMFS_OUTPUT = "output.mp4"
}
