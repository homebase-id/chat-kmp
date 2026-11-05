package id.homebase.photobase.backup

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/** The time a trigger allows a run; null [limit] means until the work is done (foreground). */
class BackupBudget(limit: Duration?, timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic) {
    private val deadline: ComparableTimeMark? = limit?.let { timeSource.markNow() + it }

    val remaining: Duration
        get() = deadline?.let { -it.elapsedNow() } ?: Duration.INFINITE

    val isExpired: Boolean
        get() = remaining <= Duration.ZERO
}

enum class BackupTrigger { Foreground, UserRequested, MediaChanged, Periodic, Overnight, Push, BackgroundRefresh, BackgroundProcessing }

data class BackupRunRequest(
    val trigger: BackupTrigger,
    val budget: BackupBudget,
    /** Foreground and long runs: list every id, which is what finds deletions. */
    val findDeletions: Boolean,
    /** Ignore the checkpoint this once: after an iOS background launch deltas can miss assets. */
    val forceFullScan: Boolean = false,
)
