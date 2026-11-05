package id.homebase.photobase.backup

enum class BackupPauseReason {
    BackupOff,
    NoLibraryAccess,
    SignedOut,
    NoNetwork,
    WaitingForWifi,
    WaitingForCharging,
    LowBattery,
    LowPowerMode,
    StorageFull,
}

enum class LibraryAccess { None, Limited, Full }

data class BackupFailure(val deviceAssetId: String, val reason: String)

data class BackupStatus(
    val isRunning: Boolean = false,
    val pauseReason: BackupPauseReason? = null,
    val lastSuccessMs: Long? = null,
    val pendingCount: Long = 0,
    val uploadingCount: Long = 0,
    val backedUpCount: Long = 0,
    val failures: List<BackupFailure> = emptyList(),
    val failedCount: Long = 0,
    val libraryAccess: LibraryAccess = LibraryAccess.None,
) {
    val totalCount: Long
        get() = backedUpCount + pendingCount + uploadingCount

    val progress: Float
        get() = if (totalCount == 0L) 1f else backedUpCount.toFloat() / totalCount

    /** As of the last library check, which is as much as this device can know. */
    val isAllBackedUp: Boolean
        get() = pendingCount == 0L && uploadingCount == 0L && failedCount == 0L &&
            (backedUpCount > 0 || lastSuccessMs != null)
}

enum class BackupIndicator { Working, UpToDate, Paused, NeedsAttention }

/** What the gallery header shows; null shows nothing. */
fun backupIndicatorOf(status: BackupStatus, backupEnabled: Boolean): BackupIndicator? = when {
    !backupEnabled -> null
    status.pauseReason == BackupPauseReason.StorageFull || status.pauseReason == BackupPauseReason.NoLibraryAccess ->
        BackupIndicator.NeedsAttention
    status.isRunning || status.uploadingCount > 0 -> BackupIndicator.Working
    status.failedCount > 0 -> BackupIndicator.NeedsAttention
    status.pauseReason == BackupPauseReason.BackupOff || status.pauseReason == BackupPauseReason.SignedOut -> null
    status.pauseReason != null -> BackupIndicator.Paused
    status.isAllBackedUp -> BackupIndicator.UpToDate
    status.pendingCount > 0 -> BackupIndicator.Paused
    else -> null
}

data class DeviceState(
    val hasNetwork: Boolean,
    val isUnmetered: Boolean,
    val isCharging: Boolean,
    val isBatteryLow: Boolean,
    val isLowPowerMode: Boolean,
)

/**
 * The device facts backup depends on.
 *
 * Contract for every implementation: [state] answers for the moment of the call and [libraryAccess]
 * never raises the system permission dialog. Neither one suspends for longer than a few seconds.
 */
interface BackupDeviceProbe {
    suspend fun state(): DeviceState

    fun libraryAccess(): LibraryAccess
}

/** [interactive]: the user has the app open, which lifts the battery conditions but not Wi-Fi only. */
fun backupPauseReason(settings: BackupSettings, state: DeviceState, interactive: Boolean): BackupPauseReason? = when {
    !state.hasNetwork -> BackupPauseReason.NoNetwork
    settings.wifiOnly && !state.isUnmetered -> BackupPauseReason.WaitingForWifi
    interactive -> null
    settings.chargingOnly && !state.isCharging -> BackupPauseReason.WaitingForCharging
    state.isLowPowerMode && !state.isCharging -> BackupPauseReason.LowPowerMode
    state.isBatteryLow && !state.isCharging -> BackupPauseReason.LowBattery
    else -> null
}

fun networkAllowsVideos(settings: BackupSettings, state: DeviceState): Boolean =
    state.isUnmetered || settings.videosOnCellular

fun backupVideosAllowed(settings: BackupSettings, state: DeviceState, interactive: Boolean): Boolean {
    return networkAllowsVideos(settings, state) && (interactive || state.isCharging)
}
