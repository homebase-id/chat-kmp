package id.homebase.core.diagnostics

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** The last thing a process managed to record about itself before it stopped ticking. */
internal data class Heartbeat(val epochMs: Long, val foreground: Boolean)

internal fun encodeHeartbeat(beat: Heartbeat): String =
    "${beat.epochMs} ${if (beat.foreground) FOREGROUND_MARK else BACKGROUND_MARK}"

internal fun decodeHeartbeat(line: String?): Heartbeat? {
    val parts = line?.trim()?.split(' ') ?: return null
    if (parts.size != 2) return null
    val epochMs = parts[0].toLongOrNull() ?: return null
    val foreground = when (parts[1]) {
        FOREGROUND_MARK -> true
        BACKGROUND_MARK -> false
        else -> return null
    }
    return Heartbeat(epochMs, foreground)
}

/**
 * The breadcrumb the *next* process writes about the one that died, or `null` when the previous
 * exit is unremarkable.
 *
 * A background beat is never reported: iOS suspends a process only after backgrounding it, and
 * backgrounding posts `UIApplicationDidEnterBackground`, which writes `foreground=false`. Every
 * ordinary end-of-life — user leaves the app, OS suspends it, jetsam collects it later — passes
 * through that transition, so gating on the flag is what keeps this out of the routine-launch
 * noise the way `stalled ~986967ms` was not.
 */
internal fun renderProcessDeathMessage(previous: Heartbeat?, launchEpochMs: Long): String? {
    if (previous == null || !previous.foreground) return null
    return "Previous process ended on screen without ever recording a background transition — " +
        "its last heartbeat was ${launchEpochMs - previous.epochMs}ms before this launch. An OS " +
        "suspension always backgrounds first, so that process was killed or died while it was " +
        "still foreground: read the tail of its session above for how far it got."
}

/**
 * Cross-process half of [MainThreadWatchdog], for the stall a process never lives to report.
 *
 * Both in-process detectors report after the fact and both need `workDispatcher` to resume
 * them — the main-thread sentinel to time out, the wall-clock-gap check to read its gap once
 * `delay()` returns. A process killed *during* the stall (force-quit, jetsam) therefore writes
 * nothing at all, which is exactly what #1491's 47s iOS freeze produced: silence, then a fresh
 * `APP STARTUP`. The record has to outlive the process, so it goes to disk.
 *
 * Beats are written from the watchdog's own loop, so a wedged dispatcher stops them by
 * construction, and from the foreground observer, so a wedged main thread does too.
 */
class ProcessHeartbeat(
    private val file: Path,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    @Volatile
    private var foreground = false

    private val previous: Heartbeat? = decodeHeartbeat(read())

    /** The line to log about the previous process, or `null` if it ended cleanly. */
    fun postMortem(): String? = renderProcessDeathMessage(previous, nowEpochMs())

    fun setForeground(value: Boolean) {
        foreground = value
        beat()
    }

    fun beat() {
        val line = encodeHeartbeat(Heartbeat(nowEpochMs(), foreground))
        runCatching { SystemFileSystem.sink(file).buffered().use { it.writeString(line) } }
    }

    private fun read(): String? = runCatching {
        if (SystemFileSystem.metadataOrNull(file) == null) null
        else SystemFileSystem.source(file).buffered().use { it.readString() }
    }.getOrNull()
}

private const val FOREGROUND_MARK = "F"
private const val BACKGROUND_MARK = "B"
