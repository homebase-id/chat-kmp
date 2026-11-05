package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the app and the settings screen ask of backup. Null [run] where backup doesn't exist: desktop and web. */
class PhotoBackupController(
    private val run: PhotoBackupRun?,
    private val source: DeviceChangeSource?,
    private val settingsStore: BackupSettingsStore,
    private val pushSchedule: BackupPushSchedule,
    private val results: BackupOutboxResults,
    private val scheduler: BackupScheduler,
    private val scope: CoroutineScope,
) {
    private var foregroundJob: Job? = null

    private var wokenInBackgroundSinceForeground = false

    val isSupported: Boolean get() = run != null

    val status: StateFlow<BackupStatus>? get() = run?.status

    val settings: StateFlow<BackupSettings> get() = settingsStore.settings

    fun onAppForeground() {
        val run = run ?: return
        val settings = settingsStore.settings.value
        if (!settings.enabled) {
            scope.launch { run.refreshStatus() }
            return
        }
        val fullScan = wokenInBackgroundSinceForeground
        wokenInBackgroundSinceForeground = false
        foregroundJob = scope.launch {
            pushSchedule.ensure(settings)
            run.run(BackupRunRequest(BackupTrigger.Foreground, BackupBudget(null), findDeletions = true, forceFullScan = fullScan))
        }
    }

    fun refreshStatus() {
        val run = run ?: return
        scope.launch { run.refreshStatus() }
    }

    /** Off screen a foreground run has no time budget at all, so it stops and a background trigger takes over. */
    fun onAppBackground() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    suspend fun runFromBackground(request: BackupRunRequest): BackupRunOutcome? {
        wokenInBackgroundSinceForeground = true
        return run?.run(request)
    }

    fun backUpNow() {
        val run = run ?: return
        foregroundJob = scope.launch {
            run.run(BackupRunRequest(BackupTrigger.UserRequested, BackupBudget(null), findDeletions = true))
        }
    }

    fun retryFailures() {
        val run = run ?: return
        scope.launch {
            run.retryFailures()
            backUpNow()
        }
    }

    fun update(transform: (BackupSettings) -> BackupSettings) {
        scope.launch {
            val before = settingsStore.settings.value
            settingsStore.update(transform)
            val after = settingsStore.settings.value
            scheduler.rearm(after)
            when {
                before.enabled && !after.enabled -> {
                    pushSchedule.cancel()
                    foregroundJob?.cancel()
                }
                !before.enabled && after.enabled -> {
                    pushSchedule.schedule(after)
                    backUpNow()
                }
                after.enabled && before.pushIntervalMs != after.pushIntervalMs -> pushSchedule.schedule(after)
            }
        }
    }

    suspend fun albums(): List<DeviceAlbum> = source?.albums().orEmpty()

    /** Runs before logout wipes anything: nothing here may outlive the identity. */
    suspend fun onBeforeLogout() {
        foregroundJob?.cancel()
        run?.cancelActive()
        scheduler.rearm(BackupSettings(enabled = false))
        if (settingsStore.settings.value.enabled) pushSchedule.cancelBeforeLogout()
    }

    fun onPostAuthenticated() {
        foregroundJob?.cancel()
        wokenInBackgroundSinceForeground = false
        settingsStore.reset()
        results.reset()
        Logger.d(tag = TAG) { "reset for the signed-in identity" }
        val run = run ?: return
        results.start()
        scope.launch {
            run.resetStatus()
            scheduler.rearm(settingsStore.settings.value)
        }
    }

    private companion object {
        const val TAG = "PhotoBackupController"
    }
}
