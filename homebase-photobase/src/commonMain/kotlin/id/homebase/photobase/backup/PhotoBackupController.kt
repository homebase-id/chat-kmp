package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.photobase.PhotobaseConfigFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the app and the settings screen ask of backup. Null [run] where backup doesn't exist: desktop and web. */
class PhotoBackupController(
    private val run: PhotoBackupRun?,
    private val source: DeviceChangeSource?,
    private val settingsStore: BackupSettingsStore,
    private val pushSchedule: BackupPushSchedule,
    private val configFile: PhotobaseConfigFile,
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
            pushSchedule.ensure(adoptSharedInterval())
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
                    pushSchedule.schedule(adoptSharedInterval())
                    backUpNow()
                }
            }
        }
    }

    /** Throws when the server can't be asked: the caller says so rather than showing a stale value as current. */
    suspend fun loadPushInterval() {
        configFile.read()?.pushIntervalMs?.let { adopt(it) }
    }

    /** Off the caller's scope: leaving the page must not abandon a save between the file and the schedule. */
    suspend fun setPushInterval(intervalMs: Long) = scope.async {
        val clamped = intervalMs.coerceAtLeast(BackupSettings.MIN_PUSH_INTERVAL.inWholeMilliseconds)
        configFile.update { it.copy(pushIntervalMs = clamped) }
        adopt(clamped)
        pushSchedule.correctIfScheduled(settingsStore.settings.value)
    }.await()

    private suspend fun adoptSharedInterval(): BackupSettings {
        try {
            loadPushInterval()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "could not read the shared interval; keeping this device's" }
        }
        return settingsStore.settings.value
    }

    private suspend fun adopt(intervalMs: Long) {
        if (settingsStore.settings.value.pushIntervalMs != intervalMs) {
            settingsStore.update { it.copy(pushIntervalMs = intervalMs) }
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
