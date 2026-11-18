package id.homebase.api.video

import co.touchlab.kermit.Logger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val SESSION_STOP_LIMIT = 30.seconds
private val SESSION_CANCEL_RETRY = 500.milliseconds

/**
 * Waits for an engine session, and when cancelled returns only once it has really stopped:
 * [VideoCompressionService]'s lock has to outlast the session.
 */
internal suspend fun <T> awaitFfmpegSession(finished: CompletableDeferred<T>, sessionId: Long, cancel: () -> Unit): T {
    try {
        return finished.await()
    } catch (e: CancellationException) {
        withContext(NonCancellable) {
            val stopping = TimeSource.Monotonic.markNow()
            val stopped = withTimeoutOrNull(SESSION_STOP_LIMIT) {
                while (!finished.isCompleted) {
                    // A cancel does nothing until the session is running, so it's repeated.
                    try { cancel() } catch (_: Exception) {}
                    withTimeoutOrNull(SESSION_CANCEL_RETRY) { finished.await() }
                }
            } != null
            if (stopped) {
                Logger.i(tag = "FFmpegUtils") { "ffmpeg session $sessionId stopped ${stopping.elapsedNow()} after cancel" }
            } else {
                Logger.e(tag = "FFmpegUtils") { "ffmpeg session $sessionId still running $SESSION_STOP_LIMIT after cancel; carrying on so sends aren't stuck" }
            }
        }
        throw e
    }
}
