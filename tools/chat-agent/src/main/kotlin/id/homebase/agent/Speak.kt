package id.homebase.agent

import id.homebase.api.util.truncateToCodePoints
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val DEFAULT_TTS_TIMEOUT_MS = 240_000L
const val DEFAULT_TTS_MAX_CHARS = 400
const val DEFAULT_MAX_VOICE_PER_DAY = 20
const val DEFAULT_TTS_PRESET = "expressive"
const val TTS_SIDECAR_MAX_CHARS = 600
const val VOICE_PROMPT_LINE = "Your replies may be spoken aloud as voice notes: keep them short and conversational, in plain sentences without markdown or links."
val TTS_PRESETS = setOf("stable", "expressive")

private val CODE_FENCE = Regex("```[\\s\\S]*?(```|\\z)")
private val MD_LINK = Regex("!?\\[([^\\]]*)]\\(\\s*https?://[^)\\s]*\\s*\\)")
private val BARE_URL = Regex("https?://\\S+")
private val ATTACH_LINES = Regex("(?m)^\\s*ATTACH:.*$")
private val MD_MARKS = Regex("(?m)(\\*\\*|__|~~|`|^\\s{0,3}#{1,6}\\s+|^\\s*>\\s?|^\\s*[-*]\\s+)")
private val SPACES = Regex("\\s+")

fun speakableText(text: String, maxChars: Int): String =
    text.replace(ATTACH_LINES, " ")
        .replace(CODE_FENCE, " ")
        .replace(MD_LINK, "$1")
        .replace(BARE_URL, "link")
        .replace(MD_MARKS, "")
        .replace(SPACES, " ")
        .trim()
        .truncateToCodePoints(maxChars)

fun ttsEnvironment(config: AgentConfig): Map<String, String> = buildMap {
    if (config.ttsReference != null) {
        put("TTS_REFERENCE", config.ttsReference)
        config.ttsReferenceText?.let { put("TTS_REFERENCE_TEXT", it) }
    } else {
        put("TTS_VOICE", config.ttsVoice)
    }
    put("TTS_PRESET", config.ttsPreset)
}

fun shellSpeaker(
    command: String,
    env: Map<String, String>,
    timeoutMs: Long,
    log: (String) -> Unit = {},
    maxChars: Int = DEFAULT_TTS_MAX_CHARS,
    ffmpeg: Ffmpeg = DEFAULT_FFMPEG,
): suspend (String) -> OutVoice? = { raw ->
    withContext(Dispatchers.IO) {
        fun fail(reason: String): OutVoice? = null.also { log("speak failed: $reason") }
        val text = speakableText(raw, maxChars)
        if (text.isEmpty()) return@withContext fail("nothing speakable in the reply")
        val dir = tempDir("speak")
        try {
            val input = File(dir, "text.txt").also { it.writeText(text) }
            val output = File(dir, "reply.m4a")
            val builder = ProcessBuilder("sh", "-c", "$command \"\$1\" \"\$2\"", "speak", input.absolutePath, output.absolutePath)
            builder.environment().putAll(env)
            val process = builder.start()
            process.outputStream.close()
            val out = process.inputStream.readTextAsync()
            val err = process.errorStream.readTextAsync()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                return@withContext fail("timed out after ${timeoutMs / 1000.0}s")
            }
            if (process.exitValue() != 0) return@withContext fail("exit ${process.exitValue()}: ${err.tail().ifEmpty { out.tail() }}")
            if (!output.isFile || output.length() == 0L) return@withContext fail("empty output: ${err.tail()}")
            if (output.length() > VOICE_MAX_BYTES) return@withContext fail("output is ${output.length()} bytes, over the $VOICE_MAX_BYTES cap")
            val type = VOICE_TYPES[output.extension.lowercase()] ?: return@withContext fail("unknown audio type .${output.extension}")
            val seconds = if (ffmpeg.available) ffmpeg.probe(output)?.let { ((it.durationMs + 500) / 1000).toInt() } else null
            OutVoice(output.name, output.readBytes(), type, seconds ?: 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}

class VoiceLimiter(
    private val file: File?,
    private val perDay: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val stamps = ArrayList<Long>()

    init {
        val cutoff = now() - DAY_MS
        file?.takeIf { it.exists() }?.readLines()?.mapNotNull { it.trim().toLongOrNull() }?.filterTo(stamps) { it >= cutoff }
    }

    @Synchronized
    fun allows(): Boolean {
        stamps.removeAll { it < now() - DAY_MS }
        return stamps.size < perDay
    }

    @Synchronized
    fun record() {
        stamps += now()
        file?.let { atomicWrite(it, stamps.joinToString("\n", postfix = "\n")) }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

class VoiceOut(private val speaker: suspend (String) -> OutVoice?, private val limiter: VoiceLimiter) {
    suspend fun speak(text: String): OutVoice {
        require(limiter.allows()) { "the daily voice reply limit is reached" }
        val voice = speaker(text) ?: throw IllegalArgumentException("voice synthesis failed, reply in text instead")
        limiter.record()
        return voice
    }
}
