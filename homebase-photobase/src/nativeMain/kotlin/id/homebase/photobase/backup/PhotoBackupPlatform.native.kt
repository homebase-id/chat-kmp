package id.homebase.photobase.backup

import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

// iOS backs up only while the app is open until its background triggers land.
private object ForegroundOnlyTriggers : BackupTriggers {
    override fun rearm(settings: BackupSettings) = Unit

    override suspend fun onWake(remaining: Duration, run: suspend (BackupRunRequest) -> Unit) = Unit

    override fun startUserRequestedRun(settings: BackupSettings, progress: Flow<Float>, runInApp: suspend () -> Unit) = false

    override fun batteryGuidance(): BatteryGuidance? = null
}

actual fun createPhotoBackupPlatform(fileOps: FileOperationsProvider): PhotoBackupPlatform? =
    PhotoBackupPlatform(IosDeviceChangeSource(fileOps), IosBackupDeviceProbe(), ForegroundOnlyTriggers)
