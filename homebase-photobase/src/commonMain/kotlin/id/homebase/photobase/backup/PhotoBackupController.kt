package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.photobase.PhotobaseConfigFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the app and the settings screen ask of backup. Null [run] where backup doesn't exist: desktop and web. */
class PhotoBackupController(
    private val run: PhotoBackupRun?,
    private val settingsStore: BackupSettingsStore,
    private val pushSchedule: BackupPushSchedule,
    private val configFile: PhotobaseConfigFile,
    private val results: BackupOutboxResults,
    private val triggers: BackupTriggers?,
    private val scope: CoroutineScope,
) {
    private var foregroundJob: Job? = null

    private var inForeground = false

    private var wokenInBackgroundSinceForeground = false

    init {
        run?.let { scope.launch { it.followLedger() } }
    }

    val isSupported: Boolean get() = run != null

    val status: StateFlow<BackupStatus>? get() = run?.status

    val settings: StateFlow<BackupSettings> get() = settingsStore.settings

    fun onAppForeground() {
        inForeground = true
        val run = run ?: return
        if (foregroundJob?.isActive == true) return
        val settings = settingsStore.settings.value
        if (!settings.enabled) {
            scope.launch { run.refreshStatus() }
            return
        }
        val fullScan = wokenInBackgroundSinceForeground && triggers?.rescansAfterBackgroundWake == true
        wokenInBackgroundSinceForeground = false
        foregroundJob = scope.launch {
            launch { pushSchedule.ensure(adoptSharedInterval()) }
            run.run(BackupRunRequest(BackupTrigger.Foreground, BackupBudget(null), forceFullScan = fullScan))
        }
    }

    suspend fun freshStatus(): BackupStatus? {
        run?.refreshStatus()
        return status?.value
    }

    fun refreshStatus() {
        val run = run ?: return
        scope.launch { run.refreshStatus() }
    }

    /** Off screen a foreground run has no time budget at all, so it stops and a background trigger takes over. */
    fun onAppBackground() {
        inForeground = false
        foregroundJob?.cancel()
        foregroundJob = null
    }

    suspend fun runFromBackground(request: BackupRunRequest) {
        wokenInBackgroundSinceForeground = true
        // Another phone turning backup off or logging out cancels the identity's one schedule.
        if (settingsStore.settings.value.enabled && request.trigger.restoresPushSchedule) {
            scope.launch { pushSchedule.ensure(adoptSharedInterval()) }
        }
        run?.run(request)
    }

    fun backUpNow() {
        val run = run ?: return
        val inApp: suspend () -> Unit = {
            run.run(BackupRunRequest(BackupTrigger.UserRequested, BackupBudget(null)))
        }
        val progress = run.status.map { it.progress }
        if (triggers?.startUserRequestedRun(settingsStore.settings.value, progress, inApp) != true) {
            foregroundJob = scope.launch { inApp() }
        }
    }

    /** One call from a push: enqueues on Android, runs in what's left of the wake on iOS. */
    suspend fun onWake(remaining: Duration, isBackupPush: Boolean = true) {
        val triggers = triggers ?: return
        if (!settingsStore.settings.value.enabled || remaining < MIN_WAKE) return
        if (!isBackupPush && (!triggers.usesOtherPushes || run?.takeOtherPushWake(OTHER_PUSH_WAKE_GAP) != true)) return
        triggers.onWake(remaining) { request -> runFromBackground(request) }
    }

    /** Re-arms what a process death can lose. */
    fun onProcessStart() {
        val settings = settingsStore.settings.value
        if (settings.enabled) triggers?.rearm(settings)
    }

    fun batteryGuidance(): BatteryGuidance? = triggers?.batteryGuidance()

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
            triggers?.rearm(after)
            when {
                before.enabled && !after.enabled -> {
                    pushSchedule.cancel()
                    foregroundJob?.cancel()
                }
                !before.enabled && after.enabled -> {
                    pushSchedule.schedule(adoptSharedInterval())
                    backUpNow()
                }
                after.enabled && (before.albumIds != after.albumIds || before.onlyNewFromMs != after.onlyNewFromMs) -> backUpNow()
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

    suspend fun albums(): List<DeviceAlbum> = run?.albums().orEmpty()

    /** Runs before logout wipes anything: nothing here may outlive the identity. */
    suspend fun onBeforeLogout(hasSession: Boolean = true) {
        foregroundJob?.cancel()
        run?.cancelActive()
        triggers?.rearm(BackupSettings(enabled = false))
        // Without a session the schedule can't be cancelled, so it keeps firing until another phone or login replaces it.
        if (hasSession && settingsStore.settings.value.enabled) pushSchedule.cancelBeforeLogout()
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
            triggers?.rearm(settingsStore.settings.value)
            if (inForeground) onAppForeground()
        }
    }

    private companion object {
        val OTHER_PUSH_WAKE_GAP = 30.minutes

        const val TAG = "PhotoBackupController"
        val MIN_WAKE = 3.seconds
    }
}
