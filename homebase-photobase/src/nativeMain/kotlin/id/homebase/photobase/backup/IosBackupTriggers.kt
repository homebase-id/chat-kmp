package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.platform.BackgroundUploadBridgeHolder
import id.homebase.api.platform.ContinuedProcessingBridgeHolder
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Also listed in Info.plist's BGTaskSchedulerPermittedIdentifiers. Registered from `AppDelegate`, before launch finishes. */
val backupRefreshTaskId: String get() = "${NSBundle.mainBundle.bundleIdentifier}.backup.refresh"
val backupProcessingTaskId: String get() = "${NSBundle.mainBundle.bundleIdentifier}.backup.processing"

class IosBackupTriggers : BackupTriggers {
    private val scope = supervisedScope("photo-backup-ios", ioDispatcher)

    override fun rearm(settings: BackupSettings) {
        val scheduler = BGTaskScheduler.sharedScheduler
        if (!settings.enabled) {
            scheduler.cancelTaskRequestWithIdentifier(backupRefreshTaskId)
            scheduler.cancelTaskRequestWithIdentifier(backupProcessingTaskId)
            BackgroundUploadBridgeHolder.bridge?.cancelAll()
            return
        }
        // One request is one launch, so every run submits the next.
        submit(BGAppRefreshTaskRequest(backupRefreshTaskId).apply {
            earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(REFRESH_EARLIEST.inWholeSeconds.toDouble())
        })
        submit(BGProcessingTaskRequest(backupProcessingTaskId).apply {
            requiresNetworkConnectivity = true
            requiresExternalPower = true
        })
    }

    override suspend fun onWake(remaining: Duration, run: suspend (BackupRunRequest) -> Unit) {
        run(BackupRunRequest(BackupTrigger.Push, BackupBudget(remaining), findDeletions = false))
    }

    /** BGContinuedProcessingTask on iOS 26+, for a user-started run with system progress. Below it, the open app runs it. */
    override fun startUserRequestedRun(settings: BackupSettings, progress: Flow<Float>, runInApp: suspend () -> Unit): Boolean {
        val bridge = ContinuedProcessingBridgeHolder.bridge ?: return false
        val handle = bridge.begin(
            name = CONTINUED_TASK_NAME,
            title = CONTINUED_TASK_TITLE,
            log = { Logger.i(tag = TAG) { it } },
            onStarted = { },
        ) ?: return false
        scope.launch {
            val reporter = launch { progress.collect(handle::reportProgress) }
            try {
                runInApp()
            } finally {
                reporter.cancel()
                handle.end()
            }
        }
        return true
    }

    override fun batteryGuidance(): BatteryGuidance? = null

    private fun submit(request: BGTaskRequest) = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        // Refused in the simulator, or without Background App Refresh. A missed trigger only costs latency.
        if (!BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)) {
            Logger.w(tag = TAG) { "${request.identifier} not scheduled: ${error.value?.localizedDescription}" }
        }
    }

    private companion object {
        const val TAG = "IosBackupTriggers"
        const val CONTINUED_TASK_NAME = "photo-backup"
        const val CONTINUED_TASK_TITLE = "Backing up photos"
        val REFRESH_EARLIEST = 15.minutes
    }
}
