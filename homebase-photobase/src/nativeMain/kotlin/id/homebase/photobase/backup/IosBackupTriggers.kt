package id.homebase.photobase.backup

import platform.darwin.NSObject
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNMutableNotificationContent
import platform.MetricKit.MXMetricPayload
import platform.MetricKit.MXMetricManagerSubscriberProtocol
import platform.MetricKit.MXMetricManager
import org.jetbrains.compose.resources.getString
import id.homebase.resources.photobase_stuck_title
import id.homebase.resources.photobase_stuck_body
import id.homebase.resources.MR
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

private val REFRESH_EARLIEST = 15.minutes

/** One request is one launch, so each launch has to submit the next. */
fun submitBackupTasks() {
    submit(BGAppRefreshTaskRequest(backupRefreshTaskId).apply {
        earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(REFRESH_EARLIEST.inWholeSeconds.toDouble())
    })
    submit(BGProcessingTaskRequest(backupProcessingTaskId).apply {
        requiresNetworkConnectivity = true
        requiresExternalPower = true
    })
}

private fun submit(request: BGTaskRequest) = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    // Refused in the simulator, or without Background App Refresh. A missed trigger only costs latency.
    if (!BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)) {
        Logger.w(tag = "IosBackupTriggers") { "${request.identifier} not scheduled: ${error.value?.localizedDescription}" }
    }
}

/** Why iOS ended the app in the background, delivered about daily. */
private class BackupExitReporter : NSObject(), MXMetricManagerSubscriberProtocol {
    override fun didReceiveMetricPayloads(payloads: List<*>) {
        payloads.filterIsInstance<MXMetricPayload>().forEach { payload ->
            val exits = payload.applicationExitMetrics?.backgroundExitData ?: return@forEach
            Logger.i(tag = "BackupExits") {
                "normal=${exits.cumulativeNormalAppExitCount} " +
                    "taskTimeout=${exits.cumulativeBackgroundTaskAssertionTimeoutExitCount} " +
                    "memoryLimit=${exits.cumulativeMemoryResourceLimitExitCount} " +
                    "memoryPressure=${exits.cumulativeMemoryPressureExitCount} " +
                    "cpuLimit=${exits.cumulativeCPUResourceLimitExitCount} " +
                    "watchdog=${exits.cumulativeAppWatchdogExitCount} " +
                    "lockedFile=${exits.cumulativeSuspendedWithLockedFileExitCount} " +
                    "crash=${exits.cumulativeAbnormalExitCount + exits.cumulativeBadAccessExitCount + exits.cumulativeIllegalInstructionExitCount}"
            }
        }
    }
}

class IosBackupTriggers : BackupTriggers {
    private val scope = supervisedScope("photo-backup-ios", ioDispatcher)
    private val exitReporter = BackupExitReporter().also { MXMetricManager.sharedManager.addSubscriber(it) }

    override val rescansAfterBackgroundWake: Boolean get() = true

    override fun rearm(settings: BackupSettings) {
        val scheduler = BGTaskScheduler.sharedScheduler
        if (!settings.enabled) {
            scheduler.cancelTaskRequestWithIdentifier(backupRefreshTaskId)
            scheduler.cancelTaskRequestWithIdentifier(backupProcessingTaskId)
            BackgroundUploadBridgeHolder.bridge?.cancelAll()
            UNUserNotificationCenter.currentNotificationCenter().removePendingNotificationRequestsWithIdentifiers(listOf(STUCK_ALERT_ID))
            return
        }
        submitBackupTasks()
        armStuckAlertIfMissing()
    }

    /** Works even after a force-quit: it only fires if no run has pushed it back. */
    override fun onBackupRan() {
        scope.launch { scheduleStuckAlert() }
    }

    private fun armStuckAlertIfMissing() {
        UNUserNotificationCenter.currentNotificationCenter().getPendingNotificationRequestsWithCompletionHandler { requests ->
            val armed = requests.orEmpty().any { (it as? UNNotificationRequest)?.identifier == STUCK_ALERT_ID }
            if (!armed) scope.launch { scheduleStuckAlert() }
        }
    }

    private suspend fun scheduleStuckAlert() {
        val content = UNMutableNotificationContent().apply {
            setTitle(getString(MR.string.photobase_stuck_title))
            setBody(getString(MR.string.photobase_stuck_body))
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(BackupLedger.STUCK_AFTER.inWholeSeconds.toDouble(), repeats = false)
        UNUserNotificationCenter.currentNotificationCenter()
            .addNotificationRequest(UNNotificationRequest.requestWithIdentifier(STUCK_ALERT_ID, content, trigger), null)
    }

    override suspend fun onWake(remaining: Duration, run: suspend (BackupRunRequest) -> Unit) {
        run(BackupRunRequest(BackupTrigger.Push, BackupBudget(remaining)))
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

    private companion object {
        const val TAG = "IosBackupTriggers"
        const val STUCK_ALERT_ID = "photo-backup-stuck"
        const val CONTINUED_TASK_NAME = "photo-backup"
        const val CONTINUED_TASK_TITLE = "Backing up photos"
    }
}
