package id.homebase.chat.services.calling

import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest

/**
 * The "tring tring" the caller hears while the callee has not answered. Every target plays the bytes
 * of [ringbackWav] in a loop, on the call's audio output (voice-call usage on Android, the call's
 * AVAudioSession on iOS), so it comes out of the earpiece or speaker the call itself uses.
 *
 * [start] and [stop] return immediately and may be called from any thread. [start] while playing and
 * [stop] while stopped do nothing. A device that cannot play the tone stays silent; it never throws.
 */
interface RingbackTone {
    fun start()
    fun stop()
}

/** Where calling is unsupported (web) the tone is silent, as no call ever leaves Idle there. */
expect fun platformRingbackTone(): RingbackTone

private const val SAMPLE_RATE = 8_000
private const val ON_MS = 2_000
private const val CYCLE_MS = 6_000
private const val FADE_MS = 10
private const val WAV_HEADER_BYTES = 44

/** Two seconds of 440 + 480 Hz (the North American ringback) then four of silence, as a 16-bit mono WAV that loops. */
fun ringbackWav(): ByteArray {
    val samples = SAMPLE_RATE * CYCLE_MS / 1_000
    val onSamples = SAMPLE_RATE * ON_MS / 1_000
    val fadeSamples = SAMPLE_RATE * FADE_MS / 1_000
    val out = ByteArray(WAV_HEADER_BYTES + samples * 2)
    fun putInt(at: Int, v: Int, bytes: Int) = repeat(bytes) { out[at + it] = (v shr (8 * it)).toByte() }
    "RIFF".forEachIndexed { i, c -> out[i] = c.code.toByte() }
    putInt(4, out.size - 8, 4)
    "WAVEfmt ".forEachIndexed { i, c -> out[8 + i] = c.code.toByte() }
    putInt(16, 16, 4)
    putInt(20, 1, 2)
    putInt(22, 1, 2)
    putInt(24, SAMPLE_RATE, 4)
    putInt(28, SAMPLE_RATE * 2, 4)
    putInt(32, 2, 2)
    putInt(34, 16, 2)
    "data".forEachIndexed { i, c -> out[36 + i] = c.code.toByte() }
    putInt(40, samples * 2, 4)
    for (i in 0 until onSamples) {
        val t = i.toDouble() / SAMPLE_RATE
        val fade = minOf(1.0, i.toDouble() / fadeSamples, (onSamples - 1 - i).toDouble() / fadeSamples)
        val v = (0.3 * (sin(2 * PI * 440 * t) + sin(2 * PI * 480 * t)) * fade * Short.MAX_VALUE).toInt()
        putInt(WAV_HEADER_BYTES + i * 2, v, 2)
    }
    return out
}

/** How long an outgoing call waits for the callee's Ringing before the caller hears the tone anyway. */
const val RINGBACK_FALLBACK_MS = 4_000L

/**
 * Plays [tone] once the callee's device reports ringing, or [fallbackMs] after dialing if it never
 * does, and stops it on any other state: connecting, declined, ended, failed.
 */
suspend fun Flow<CallState>.driveRingback(tone: RingbackTone, fallbackMs: Long = RINGBACK_FALLBACK_MS) {
    map { if (it.phase == CallPhase.Outgoing) it.peerRinging else null }
        .distinctUntilChanged()
        .collectLatest { dialingRinging ->
            if (dialingRinging == null) {
                tone.stop()
                return@collectLatest
            }
            if (!dialingRinging) delay(fallbackMs)
            tone.start()
        }
}
