package id.homebase.agent

import id.homebase.api.util.truncateToCodePoints
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

const val DEFAULT_CALL_FILLER = "Hang on."
const val DEFAULT_CALL_MAX_MINUTES = 15
const val CALL_BRAIN_TIMEOUT_MS = 60_000L
const val CALL_FILLER_AFTER_MS = 1_500L
const val CALL_MAX_FAILURES = 3
const val CALL_TTS_TIMEOUT_MS = 30_000L
const val CALL_REPLY_CHARS = 300
private const val CALL_HISTORY_TURNS = 6
private const val CALL_NOTES_CODEPOINTS = 300
private const val LOSING_YOU = "I'm losing you."

enum class CallEnd { GOODBYE, MAX_MINUTES, FAILURES }

interface CallAudioOut {
    suspend fun play(pcm: ByteArray)
    fun flush()
}

private val NOISE_TAG = Regex("[\\[(*][^\\])*]{0,40}[\\])*]")
private val GOODBYE = Regex("\\bgoodbye\\b", RegexOption.IGNORE_CASE)
private val PUNCTUATION = Regex("[^\\p{L}\\p{N} ]")
private val VOICE_COMMANDS = setOf("status", "cancel", "new")

fun cleanTranscript(raw: String): String? =
    raw.replace(NOISE_TAG, " ").replace(WHITESPACE, " ").trim().takeIf { it.isNotEmpty() }

fun spokenCommand(text: String, nickname: String): OperatorCommand? {
    val words = text.lowercase().replace(PUNCTUATION, " ").trim().split(WHITESPACE)
    if (words.size !in 1..2 || words.first() !in VOICE_COMMANDS) return null
    return parseOperatorCommand("@$nickname ${words.joinToString(" ")}", nickname)
}

class CallTurnLoop(
    private val peer: String,
    private val nickname: String,
    private val frames: Flow<ShortArray>,
    private val audio: CallAudioOut,
    private val transcribe: suspend (ByteArray, String) -> String?,
    private val brain: suspend (String) -> BrainOutcome,
    private val speak: suspend (String) -> ByteArray?,
    private val command: suspend (OperatorCommand) -> String?,
    private val postNotes: suspend (String) -> Unit,
    private val summarize: suspend (String) -> BrainOutcome = brain,
    private val hangup: () -> Unit,
    private val fillerText: String = DEFAULT_CALL_FILLER,
    private val maxMinutes: Int = DEFAULT_CALL_MAX_MINUTES,
    private val persona: String? = null,
    private val brainTimeoutMs: Long = CALL_BRAIN_TIMEOUT_MS,
    private val fillerAfterMs: Long = CALL_FILLER_AFTER_MS,
    private val tailMs: Long = CALL_TAIL_MS,
    private val log: (String) -> Unit = {},
) {
    private class Turn(val owner: Boolean, val text: String)

    private val turns = ArrayList<Turn>()
    private val failures = AtomicInteger()
    @Volatile private var reply: Job? = null
    @Volatile private var filler: Deferred<ByteArray?>? = null
    private val playing = AtomicInteger()

    suspend fun run(greeting: String? = null): CallEnd = coroutineScope {
        val ended = CompletableDeferred<CallEnd>()
        val utterances = Channel<Utterance>(Channel.UNLIMITED)
        val segmenter = Segmenter()
        launch {
            delay(maxMinutes * 60_000L)
            log("call reached callMaxMinutes=$maxMinutes")
            hangup()
            ended.complete(CallEnd.MAX_MINUTES)
        }
        launch {
            frames.collect { chunk ->
                for (event in segmenter.feed(chunk)) when (event) {
                    is SegmentEvent.Onset -> if (playing.get() > 0) bargeIn()
                    is SegmentEvent.Done -> { bargeIn(); utterances.trySend(event.utterance) }
                }
            }
        }
        launch {
            for (utterance in utterances) heard(utterance, ended)
        }
        val fillerJob = async(start = CoroutineStart.LAZY) { speakQuietly(fillerText) }
        filler = fillerJob
        reply = launch {
            try {
                if (greeting != null) say(greeting)
            } finally {
                fillerJob.start()
            }
        }
        ended.await().also { coroutineContext.cancelChildren() }
    }

    private fun bargeIn() {
        val active = reply?.takeIf { it.isActive } ?: return
        active.cancel()
        audio.flush()
        log("call barge-in")
    }

    private suspend fun speakQuietly(text: String): ByteArray? = try {
        speak(text)?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("call speak failed: ${e.message}")
        null
    }

    private suspend fun say(text: String): Boolean {
        val pcm = speakQuietly(text)
        if (pcm == null) return false
        play(pcm)
        return true
    }

    private suspend fun play(pcm: ByteArray) {
        playing.incrementAndGet()
        try {
            audio.play(pcm)
        } finally {
            playing.decrementAndGet()
        }
    }

    private suspend fun heard(utterance: Utterance, ended: CompletableDeferred<CallEnd>) {
        val raw = try {
            transcribe(wavBytes(utterance.pcm16k), "utterance.wav")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("call transcribe error: ${e.message}")
            null
        }
        if (raw == null) return failed("transcribe", ended)
        val text = cleanTranscript(raw) ?: return
        log("call heard ${text.length} chars in ${utterance.durationMs} ms")
        turns += Turn(true, text)
        coroutineScope {
            val job = launch { respond(text, ended) }
            reply = job
            job.join()
        }
    }

    private suspend fun failed(what: String, ended: CompletableDeferred<CallEnd>) {
        val n = failures.incrementAndGet()
        log("call $what failed ($n of $CALL_MAX_FAILURES)")
        if (n < CALL_MAX_FAILURES) return
        say(LOSING_YOU)
        delay(tailMs)
        hangup()
        ended.complete(CallEnd.FAILURES)
    }

    private suspend fun respond(text: String, ended: CompletableDeferred<CallEnd>) {
        val command = spokenCommand(text, nickname)?.let { command(it) }?.let(::sanitizeReply)
        val answer = command ?: think(text, ended) ?: return
        if (answer.isEmpty()) return
        val spoken = speakableText(answer, CALL_REPLY_CHARS)
        if (spoken.isEmpty()) return
        log("call replying ${spoken.length} chars")
        if (!say(spoken)) return failed("speech", ended)
        turns += Turn(false, spoken)
        failures.set(0)
        if (GOODBYE.containsMatchIn(answer)) {
            delay(tailMs)
            hangup()
            ended.complete(CallEnd.GOODBYE)
        }
    }

    private suspend fun think(text: String, ended: CompletableDeferred<CallEnd>): String? = coroutineScope {
        val fillerStarted = AtomicBoolean()
        val hold = launch {
            delay(fillerAfterMs)
            val pcm = filler?.takeIf { it.isCompleted }?.await() ?: return@launch
            fillerStarted.set(true)
            play(pcm)
        }
        val outcome = try {
            withTimeoutOrNull(brainTimeoutMs) { brain(callPrompt()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BrainOutcome.Failed(e.message ?: "brain error")
        }
        if (!fillerStarted.get()) hold.cancel()
        when (outcome) {
            null -> { failed("brain (timed out after ${brainTimeoutMs / 1000}s)", ended); null }
            is BrainOutcome.Failed -> { failed("brain (${failureLine(outcome.reason)})", ended); null }
            is BrainOutcome.Output -> classifyReply(outcome.stdout).let { if (it.kind == ReplyKind.TEXT) it.text else "" }
        }
    }

    private fun callPrompt(): String = buildString {
        appendLine("You are on a live voice call with $peer. Answer in at most two short spoken sentences, no markdown. Say GOODBYE to end the call.")
        persona?.let { appendLine(it) }
        appendLine("Call so far, oldest first (speech to text, may contain mistakes):")
        turns.takeLast(CALL_HISTORY_TURNS).forEach { appendLine("${if (it.owner) peer else "You"}: ${it.text}") }
    }

    suspend fun postSummary() {
        val lines = turns.toList()
        if (lines.none { it.owner }) return
        val prompt = buildString {
            appendLine("Summarize this finished voice call in one short line for the chat history: what $peer asked for and what was decided. Plain text, no markdown, no tools.")
            lines.takeLast(CALL_HISTORY_TURNS * 2).forEach { appendLine("${if (it.owner) peer else "You"}: ${it.text}") }
        }
        val outcome = try {
            withTimeoutOrNull(brainTimeoutMs) { summarize(prompt) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val summary = (outcome as? BrainOutcome.Output)?.let { classifyReply(it.stdout) }?.takeIf { it.kind == ReplyKind.TEXT }?.text?.oneLine(CALL_NOTES_CODEPOINTS)
            ?: "${lines.count { it.owner }} owner turns, last: ${lines.last { it.owner }.text.oneLine(80)}"
        try {
            postNotes("call notes: $summary".truncateToCodePoints(CALL_NOTES_CODEPOINTS))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("call notes not posted: ${e.message}")
        }
    }
}
