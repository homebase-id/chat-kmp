@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.api.video

import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.await

/* ------------------------------------------------------------------------------------------
 * JS interop with the `globalThis.__odinFfmpeg` bridge defined in webApp's `odin-ffmpeg.js`.
 *
 * ffmpeg.wasm runs in its own web worker, so every call is async (Promise). Byte payloads
 * cross the boundary as Base64 strings — same idiom as WebSqlDriver / ImageUtil.web.kt; it
 * keeps the boundary copy-free of typed-array ownership concerns. (A direct Uint8Array bridge
 * for the two largest crossings is a planned follow-up if large-clip compression is slow.)
 * ------------------------------------------------------------------------------------------ */

private fun ffIsLoaded(): Boolean = js("globalThis.__odinFfmpeg.isLoaded()")
private fun ffProbe(b64: String): Promise<JsString> = js("globalThis.__odinFfmpeg.probe(b64)")
private fun ffWriteFile(path: String, b64: String): Promise<JsAny?> = js("globalThis.__odinFfmpeg.writeFile(path, b64)")
private fun ffWriteText(path: String, text: String): Promise<JsAny?> = js("globalThis.__odinFfmpeg.writeText(path, text)")
private fun ffReadFile(path: String): Promise<JsString> = js("globalThis.__odinFfmpeg.readFile(path)")
private fun ffDeleteFile(path: String): Promise<JsAny?> = js("globalThis.__odinFfmpeg.deleteFile(path)")
private fun ffExec(argsJson: String): Promise<JsString> = js("globalThis.__odinFfmpeg.exec(argsJson)")
private fun ffSetProgress(cb: (Double) -> Unit): Unit = js("globalThis.__odinFfmpeg.setProgress(cb)")
private fun ffClearProgress(): Unit = js("globalThis.__odinFfmpeg.setProgress(null)")
private fun ffClearLog(): Unit = js("globalThis.__odinFfmpeg.clearLog()")
private fun ffGetLog(): String = js("globalThis.__odinFfmpeg.getLog()")

/** Container metadata read from mp4box, in the raw form the planner expects (pre-rotation dims). */
internal data class VideoProbe(
    /** Short codec form ("h264"/"hevc"/…) or null when no video track / unparseable. */
    val codec: String?,
    /** Raw (pre-rotation) coded width/height in pixels, 0 if unknown. */
    val widthPx: Int,
    val heightPx: Int,
    val durationMs: Long,
    /** Rotation degrees from the tkhd display matrix (0/±90/180). */
    val rotationDegrees: Int,
)

/**
 * Suspend wrapper over the `__odinFfmpeg` JS bridge. All file ops act on ffmpeg.wasm's
 * in-worker MEMFS (NOT okio); callers bridge bytes in/out via [writeFile]/[readFile].
 */
internal object FFmpegBridge {

    fun isCoreLoaded(): Boolean = ffIsLoaded()

    /** mp4box probe; null when the input isn't a parseable mp4/mov. Does not load the core. */
    suspend fun probe(bytes: ByteArray): VideoProbe? {
        val raw = ffProbe(Base64.encode(bytes)).await<JsString>().toString()
        if (raw.isBlank()) return null
        val parts = raw.split(";")
        if (parts.size < 5) return null
        return VideoProbe(
            codec = parts[0].ifBlank { null },
            widthPx = parts[1].toIntOrNull() ?: 0,
            heightPx = parts[2].toIntOrNull() ?: 0,
            durationMs = parts[3].toLongOrNull() ?: 0L,
            rotationDegrees = parts[4].toIntOrNull() ?: 0,
        )
    }

    suspend fun writeFile(path: String, bytes: ByteArray) {
        ffWriteFile(path, Base64.encode(bytes)).await<JsAny?>()
    }

    suspend fun writeText(path: String, text: String) {
        ffWriteText(path, text).await<JsAny?>()
    }

    suspend fun readFile(path: String): ByteArray =
        Base64.decode(ffReadFile(path).await<JsString>().toString())

    suspend fun deleteFile(path: String) {
        ffDeleteFile(path).await<JsAny?>()
    }

    /**
     * Runs ffmpeg with [args] (MEMFS-relative paths). Returns the exit code (0 = success).
     * [onProgress] receives 0..1 from ffmpeg.wasm's native progress event during the run.
     */
    suspend fun exec(args: List<String>, onProgress: ((Float) -> Unit)? = null): Int {
        if (onProgress != null) ffSetProgress { d -> onProgress(d.toFloat()) }
        try {
            return ffExec(toJsonArray(args)).await<JsString>().toString().toIntOrNull() ?: -1
        } finally {
            if (onProgress != null) ffClearProgress()
        }
    }

    /**
     * Runs `ffmpeg -version` ONLY if the core is already loaded (never forces a 22 MB load
     * just for a version string), returning the captured banner or null.
     */
    suspend fun versionBannerIfLoaded(): String? {
        if (!ffIsLoaded()) return null
        ffClearLog()
        ffExec(toJsonArray(listOf("-version"))).await<JsString>()
        return ffGetLog().ifBlank { null }
    }

    /** Minimal JSON string-array encoder (args are ffmpeg flags/paths — no exotic chars). */
    private fun toJsonArray(args: List<String>): String = buildString {
        append('[')
        for (i in args.indices) {
            if (i > 0) append(',')
            append('"')
            for (c in args[i]) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }
        append(']')
    }
}
