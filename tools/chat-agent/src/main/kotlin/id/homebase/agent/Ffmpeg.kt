package id.homebase.agent

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val FFMPEG_TIMEOUT_MS = 20_000L
const val KEYFRAMES_MAX = 4
const val VIDEO_FORMAT = "mp4"
private const val POSTER_WIDTH = 640
private const val KEYFRAME_WIDTH = 512
private const val OUTPUT_CAP = 8L * 1024 * 1024

class MediaProbe(val durationMs: Long, val width: Int, val height: Int, val codec: String)

class Ffmpeg(private val path: String? = System.getenv("PATH"), private val timeoutMs: Long = FFMPEG_TIMEOUT_MS) {
    private fun find(name: String): String? =
        path?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.map { File(it, name) }?.firstOrNull { it.isFile && it.canExecute() }?.absolutePath

    private val ffmpeg = find("ffmpeg")
    private val ffprobe = find("ffprobe")

    val available get() = ffmpeg != null && ffprobe != null

    // stderr is discarded, stdout capped and the process killed at the deadline, so a hostile file can neither flood memory nor hang a run
    private suspend fun run(cmd: List<String>, limitMs: Long = timeoutMs): ByteArray? = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        } catch (e: java.io.IOException) {
            return@withContext null
        }
        var captured: ByteArray? = null
        val reader = Thread { captured = runCatching { process.inputStream.readNBytes((OUTPUT_CAP + 1).toInt()) }.getOrNull() }.apply { isDaemon = true; start() }
        if (!process.waitFor(limitMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            return@withContext null
        }
        reader.join(1_000)
        captured?.takeIf { process.exitValue() == 0 && it.size <= OUTPUT_CAP }
    }

    // protocols limited to local files and forced demuxer for video, so attacker bytes choose neither a network fetch nor the parser
    private fun inputArgs(input: File, format: String?, seekMs: Long? = null) =
        listOf("-protocol_whitelist", "file,pipe") +
            (seekMs?.let { listOf("-ss", "%.3f".format(java.util.Locale.ROOT, it / 1000.0)) } ?: emptyList()) +
            (format?.let { listOf("-f", it) } ?: emptyList()) + listOf("-i", input.absolutePath)

    suspend fun probe(input: File, format: String? = null): MediaProbe? {
        val bin = ffprobe ?: return null
        val out = run(listOf(bin, "-v", "error", "-print_format", "json", "-show_format", "-show_streams") + inputArgs(input, format)) ?: return null
        val root = runCatching { Json.parseToJsonElement(out.decodeToString()).jsonObject }.getOrNull() ?: return null
        val streams = root["streams"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val video = streams.firstOrNull { it.str("codec_type") == "video" }
        val seconds = root["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull
            ?: streams.firstNotNullOfOrNull { it["duration"]?.jsonPrimitive?.doubleOrNull }
            ?: return null
        if (!seconds.isFinite() || seconds < 0) return null
        return MediaProbe((seconds * 1000).toLong(), video?.int("width") ?: 0, video?.int("height") ?: 0, video?.str("codec_name").orEmpty())
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull

    private suspend fun frame(input: File, atMs: Long, width: Int, limitMs: Long): ByteArray? {
        val bin = ffmpeg ?: return null
        return run(listOf(bin, "-nostdin", "-v", "error") + inputArgs(input, VIDEO_FORMAT, atMs) + listOf("-frames:v", "1", "-vf", "scale='min($width,iw)':-2", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "5", "pipe:1"), limitMs)
            ?.takeIf { it.isNotEmpty() }
    }

    suspend fun poster(input: File, durationMs: Long): ByteArray? = frame(input, minOf(1_000L, durationMs / 2), POSTER_WIDTH, timeoutMs)

    // one shared deadline across all frames
    suspend fun keyframes(input: File, durationMs: Long, count: Int = KEYFRAMES_MAX): List<ByteArray> {
        val n = count.coerceIn(1, KEYFRAMES_MAX)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val frames = ArrayList<ByteArray>()
        for (i in 0 until n) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) break
            frame(input, durationMs * (2 * i + 1) / (2L * n), KEYFRAME_WIDTH, left)?.let { frames += it }
        }
        return frames
    }
}
