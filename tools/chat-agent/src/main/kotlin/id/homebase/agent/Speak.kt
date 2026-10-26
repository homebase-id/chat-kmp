package id.homebase.agent

import id.homebase.api.util.truncateToCodePoints
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val DEFAULT_TTS_TIMEOUT_MS = 240_000L
const val DEFAULT_TTS_MAX_CHARS = 150
const val TTS_COLD_START_MS = 47_000L
const val TTS_MS_PER_CHAR = 1_000L
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
            val r = runShell(command, listOf(input.absolutePath, output.absolutePath), env + ("TMPDIR" to dir.absolutePath), timeoutMs)
            if (r.timedOut) return@withContext fail("timed out after ${timeoutMs / 1000.0}s")
            if (r.exit != 0) return@withContext fail("exit ${r.exit}: ${r.errTail.ifEmpty { r.outTail }}")
            if (!output.isFile || output.length() == 0L) return@withContext fail("empty output: ${r.errTail}")
            if (output.length() > VOICE_MAX_BYTES) return@withContext fail("output is ${output.length()} bytes, over the $VOICE_MAX_BYTES cap")
            val type = VOICE_TYPES[output.extension.lowercase()] ?: return@withContext fail("unknown audio type .${output.extension}")
            OutVoice(output.name, output.readBytes(), type, probeSeconds(ffmpeg, output) ?: 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}

private val NO_AUTHORS = emptySet<String>()

class VoiceOut(private val speaker: suspend (String) -> OutVoice?, private val limiter: RunLimiter) {
    suspend fun speak(text: String): OutVoice {
        val slot = limiter.reserve(NO_AUTHORS) ?: throw IllegalArgumentException("the daily voice reply limit is reached")
        try {
            return speaker(text) ?: throw IllegalArgumentException("voice synthesis failed, reply in text instead")
        } catch (e: Throwable) {
            limiter.release(slot)
            throw e
        }
    }
}
