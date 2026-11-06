package id.homebase.photobase.backup

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

class BatteryGuidance(val openSettings: () -> Unit)

/**
 * What a platform can do to run backup while the app isn't open.
 *
 * Contract for every implementation:
 * - [rearm] with a disabled [BackupSettings] cancels everything it armed. Otherwise it's idempotent,
 *   and called at every run's start and end and at process start.
 * - [onWake] returns promptly on a platform that moves the work elsewhere, or calls [run] within [remaining].
 * - [startUserRequestedRun] returns false when the platform has nothing better than the caller
 *   running in the open app. Otherwise it has arranged the run, using [runInApp] or its own way.
 * - [batteryGuidance] is null where there's nothing to advise.
 */
interface BackupTriggers : BackupScheduler {
    /** The change history can miss photos added during a background launch, so the next foreground run lists everything. */
    val rescansAfterBackgroundWake: Boolean get() = false

    /** A chat push is also a chance to check for photos, for a platform short of other wake-ups. */
    val usesOtherPushes: Boolean get() = false

    suspend fun onWake(remaining: Duration, run: suspend (BackupRunRequest) -> Unit)

    fun startUserRequestedRun(settings: BackupSettings, progress: Flow<Float>, runInApp: suspend () -> Unit): Boolean

    fun batteryGuidance(): BatteryGuidance?
}
