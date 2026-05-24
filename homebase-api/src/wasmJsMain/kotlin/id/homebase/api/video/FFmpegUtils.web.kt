package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.file.systemFileSystem
import kotlin.math.abs
import kotlin.random.Random
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
 * Functions: compress (via the shared [FfmpegCompressPlanner]); thumbnail and duration/rotation
 * browser-native via mp4box / <video> (no core load); HLS segment/encrypt + remux; version (lazy).
 * The core wasm is loaded lazily on the first ffmpeg op (compress/segment/remux).
 *
 * Single shared ffmpeg.wasm instance + fixed MEMFS scratch names — fine because the upload
 * pipeline runs one video op at a time; concurrent calls would collide on the worker/MEMFS.
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
    // HLS segmentation + remux.
    //
    // The AES key material (enc.key + keyinfo.txt) is written ONLY into ffmpeg's MEMFS and
    // deleted the moment ffmpeg is done — it never touches okio. Only the .m3u8 + .ts are
    // read out to okio. (deleteHlsKeyMaterial(okioDir) would be a no-op here; the key travels
    // in the message KeyHeader, not the files.) Mirrors the JVM/Android segmentInternal incl.
    // the rotation-fix re-encode for portrait sources where stream-copy would lose orientation.
    // ────────────────────────────────────────────────────────────────────────────

    actual suspend fun segmentAndEncryptVideo(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: ((Float) -> Unit)?,
    ): Pair<String, String>? = runSegment(inputPath, keyHeader, onProgress)

    actual suspend fun segmentVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Pair<String, String>? = runSegment(inputPath, keyHeader = null, onProgress = onProgress)

    /**
     * Drives ffmpeg.wasm to produce single-file HLS (index.m3u8 + index.ts). When [keyHeader]
     * is non-null, segments are AES-128 encrypted (built-in HLS crypto). Returns the okio
     * (playlistPath, segmentPath) pair, or null on failure.
     */
    private suspend fun runSegment(
        inputPath: String,
        keyHeader: KeyHeader?,
        onProgress: ((Float) -> Unit)?,
    ): Pair<String, String>? {
        val inputBytes = readOkioBytes(inputPath) ?: return null

        val rotation = FFmpegBridge.probe(inputBytes)?.rotationDegrees ?: 0
        val absRot = abs(((rotation % 360) + 360) % 360)
        val needsRotationFix = absRot == 90 || absRot == 270

        FFmpegBridge.writeFile(MEMFS_INPUT, inputBytes)

        if (keyHeader != null) {
            val aes = keyHeader.aesKey.unsafeBytes
            val iv = keyHeader.iv
            if (aes.size != 16 || iv.size != 16) {
                FFmpegBridge.deleteFile(MEMFS_INPUT)
                return null
            }
            FFmpegBridge.writeFile(HLS_KEY_FILE_NAME, aes)
            // keyinfo lines: <URI written into playlist> / <path ffmpeg opens> / <IV hex>.
            // Both paths are the MEMFS-root key name; the URI value is informational (the
            // receiver decrypts with the KeyHeader, not this URI).
            FFmpegBridge.writeText(
                HLS_KEY_INFO_FILE_NAME,
                "$HLS_KEY_FILE_NAME\n$HLS_KEY_FILE_NAME\n${iv.toHexLower()}",
            )
        }

        val args = buildList {
            add("-y")
            add("-i"); add(MEMFS_INPUT)
            if (!needsRotationFix) {
                addAll(listOf("-codec:v", "copy", "-codec:a", "copy"))
            } else {
                // Bake orientation into the pixels — MPEG-TS doesn't carry the mp4 rotation matrix.
                addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-g", "30", "-bf", "2", "-c:a", "copy"))
            }
            addAll(listOf("-hls_time", "6", "-hls_list_size", "0", "-hls_flags", "single_file"))
            if (keyHeader != null) {
                add("-hls_key_info_file"); add(HLS_KEY_INFO_FILE_NAME)
            }
            addAll(listOf("-f", "hls", "-hls_segment_filename", MEMFS_HLS_SEGMENT, MEMFS_HLS_PLAYLIST))
        }

        val status = FFmpegBridge.exec(args, onProgress)

        // Delete the plaintext key material from MEMFS the instant ffmpeg is done — on both
        // success and failure — so it never lingers in the worker filesystem.
        if (keyHeader != null) {
            FFmpegBridge.deleteFile(HLS_KEY_FILE_NAME)
            FFmpegBridge.deleteFile(HLS_KEY_INFO_FILE_NAME)
        }

        if (status != 0) {
            FFmpegBridge.deleteFile(MEMFS_INPUT)
            FFmpegBridge.deleteFile(MEMFS_HLS_PLAYLIST)
            FFmpegBridge.deleteFile(MEMFS_HLS_SEGMENT)
            return null
        }

        val playlistBytes = FFmpegBridge.readFile(MEMFS_HLS_PLAYLIST)
        val segmentBytes = FFmpegBridge.readFile(MEMFS_HLS_SEGMENT)
        FFmpegBridge.deleteFile(MEMFS_INPUT)
        FFmpegBridge.deleteFile(MEMFS_HLS_PLAYLIST)
        FFmpegBridge.deleteFile(MEMFS_HLS_SEGMENT)

        val dir = "$CACHE_DIR/hls_${randomToken()}"
        val playlistPath = writeBytesInDir(dir, "index.m3u8", playlistBytes)
        val segmentPath = writeBytesInDir(dir, "index.ts", segmentBytes)
        return playlistPath to segmentPath
    }

    /**
     * Stream-copy remux of a local (already-decrypted) HLS playlist + its .ts segment(s) into an
     * MP4. [playlistPath]/[outputPath] are okio paths; the playlist's segment references are
     * staged into MEMFS under the same names so they resolve there.
     */
    actual suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean {
        val playlistText = readOkioBytes(playlistPath)?.decodeToString() ?: return false
        val playlistDir = playlistPath.toPath().parent ?: return false
        val segNames = playlistText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .distinct()
            .toList()
        if (segNames.isEmpty()) return false

        FFmpegBridge.writeText(MEMFS_REMUX_PLAYLIST, playlistText)
        for (seg in segNames) {
            val bytes = readOkioBytes((playlistDir / seg).toString())
            if (bytes == null) {
                FFmpegBridge.deleteFile(MEMFS_REMUX_PLAYLIST)
                for (s in segNames) FFmpegBridge.deleteFile(s)
                return false
            }
            FFmpegBridge.writeFile(seg, bytes)
        }

        val args = listOf(
            "-y",
            "-allowed_extensions", "ALL",
            "-i", MEMFS_REMUX_PLAYLIST,
            "-c", "copy",
            "-bsf:a", "aac_adtstoasc",
            "-movflags", "+faststart",
            MEMFS_REMUX_OUTPUT,
        )
        val status = FFmpegBridge.exec(args)

        FFmpegBridge.deleteFile(MEMFS_REMUX_PLAYLIST)
        for (seg in segNames) FFmpegBridge.deleteFile(seg)
        if (status != 0) {
            FFmpegBridge.deleteFile(MEMFS_REMUX_OUTPUT)
            return false
        }

        val outBytes = FFmpegBridge.readFile(MEMFS_REMUX_OUTPUT)
        FFmpegBridge.deleteFile(MEMFS_REMUX_OUTPUT)
        return runCatching {
            val outPath = outputPath.toPath()
            outPath.parent?.let { systemFileSystem.createDirectories(it) }
            systemFileSystem.write(outPath) { write(outBytes) }
        }.isSuccess
    }

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

    private fun writeCacheBytes(name: String, bytes: ByteArray): String =
        writeBytesInDir(CACHE_DIR, name, bytes)

    private fun writeBytesInDir(dirPath: String, name: String, bytes: ByteArray): String {
        val dir = dirPath.toPath()
        systemFileSystem.createDirectories(dir)
        val path = dir / name
        systemFileSystem.write(path) { write(bytes) }
        return path.toString()
    }

    private fun randomToken(): String = Random.nextLong().toULong().toString(16)

    /** Lowercase hex, no separators — for the HLS keyinfo IV line (wasmJs has no String.format). */
    private fun ByteArray.toHexLower(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(hex[v ushr 4])
            sb.append(hex[v and 0x0f])
        }
        return sb.toString()
    }

    private const val MEMFS_INPUT = "input.mp4"
    private const val MEMFS_OUTPUT = "output.mp4"
    private const val MEMFS_HLS_PLAYLIST = "index.m3u8"
    private const val MEMFS_HLS_SEGMENT = "index.ts"
    private const val MEMFS_REMUX_PLAYLIST = "remux_input.m3u8"
    private const val MEMFS_REMUX_OUTPUT = "remux_output.mp4"
}
