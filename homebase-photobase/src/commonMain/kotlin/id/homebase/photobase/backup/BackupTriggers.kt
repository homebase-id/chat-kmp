package id.homebase.photobase.backup

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

class BatteryGuidance(val isRestricted: Boolean, val openSettings: () -> Unit)

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
    suspend fun onWake(remaining: Duration, run: suspend (BackupRunRequest) -> Unit)

    fun startUserRequestedRun(settings: BackupSettings, progress: Flow<Float>, runInApp: suspend () -> Unit): Boolean

    fun batteryGuidance(): BatteryGuidance?
}
