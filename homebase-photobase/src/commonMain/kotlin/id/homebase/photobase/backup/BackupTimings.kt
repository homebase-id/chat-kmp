package id.homebase.photobase.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

enum class BackupStage { Login, Scan, Export, ExportReused, Hash, ServerCheck, WaitForRoom, Prepare, Encrypt, Thumbnails, UploadBody, Enqueue, Settle }

/** One run's time per stage. Parallel workers add up, so a stage can exceed the run's total. */
@OptIn(ExperimentalAtomicApi::class)
class BackupTimings : AbstractCoroutineContextElement(BackupTimings) {
    companion object Key : CoroutineContext.Key<BackupTimings>

    private val millis = BackupStage.entries.map { AtomicLong(0L) }
    private val counts = BackupStage.entries.map { AtomicLong(0L) }

    fun record(stage: BackupStage, elapsed: Duration) {
        millis[stage.ordinal].addAndFetch(elapsed.inWholeMilliseconds)
        counts[stage.ordinal].addAndFetch(1L)
    }

    fun summary(): String = BackupStage.entries
        .filter { counts[it.ordinal].load() > 0 }
        .joinToString(" ") { "${it.name}=${millis[it.ordinal].load()}ms/${counts[it.ordinal].load()}" }
}

/** Records even when [block] is cancelled: a stage cut off by the wake's end is what the logs are for. */
suspend inline fun <T> timed(stage: BackupStage, block: () -> T): T {
    val started = TimeSource.Monotonic.markNow()
    try {
        return block()
    } finally {
        currentCoroutineContext()[BackupTimings]?.record(stage, started.elapsedNow())
    }
}
