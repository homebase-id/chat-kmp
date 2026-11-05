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

enum class BackupTrigger {
    Foreground, UserRequested, MediaChanged, Periodic, Overnight, Push, BackgroundRefresh, BackgroundProcessing;

    /** A push proves the schedule exists, and new photos can arrive too often to ask the server each time. */
    val restoresPushSchedule: Boolean get() = this != Push && this != MediaChanged
}

data class BackupRunRequest(
    val trigger: BackupTrigger,
    val budget: BackupBudget,
    /** Foreground and long runs: list every id, which is what finds deletions. */
    val findDeletions: Boolean,
    /** Ignore the checkpoint this once: after an iOS background launch deltas can miss assets. */
    val forceFullScan: Boolean = false,
) {
    /** A push or refresh window is about 25 seconds, which an iCloud download often won't fit. */
    val mayDownloadFromCloud: Boolean
        get() = trigger != BackupTrigger.Push && trigger != BackupTrigger.BackgroundRefresh

    /** The user has the app open. */
    val isInteractive: Boolean
        get() = trigger == BackupTrigger.Foreground || trigger == BackupTrigger.UserRequested
}
