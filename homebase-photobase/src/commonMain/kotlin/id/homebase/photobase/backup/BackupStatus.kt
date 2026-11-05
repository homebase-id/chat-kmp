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
    val progress: Float
        get() {
            val total = backedUpCount + pendingCount + uploadingCount
            return if (total == 0L) 1f else backedUpCount.toFloat() / total
        }
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

fun backupVideosAllowed(settings: BackupSettings, state: DeviceState, interactive: Boolean): Boolean {
    val networkOk = state.isUnmetered || settings.videosOnCellular
    return networkOk && (interactive || state.isCharging)
}
