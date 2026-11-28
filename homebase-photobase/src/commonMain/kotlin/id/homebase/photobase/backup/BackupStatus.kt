package id.homebase.photobase.backup

enum class BackupPauseReason {
    BackupOff,
    NoLibraryAccess,
    SignedOut,
    /** The Photos drive is private and this device has no key for it: only a sign-in again brings one. */
    NeedsSignIn,
    NoNetwork,
    WaitingForWifi,
    WaitingForCharging,
    LowBattery,
    LowPowerMode,
    StorageFull,
    PhoneStorageLow,
    TooHot,
}

enum class LibraryAccess { None, Limited, Full }

data class BackupFailure(val deviceAssetId: String, val reason: String)

enum class BackupWorkStage { Preparing, Uploading }

/** A photo or video this process is preparing or has handed to the Outbox; [percent] is null until it reports progress. */
data class BackupWork(val isVideo: Boolean, val stage: BackupWorkStage, val percent: Int? = null)

data class BackupStatus(
    val isRunning: Boolean = false,
    val pauseReason: BackupPauseReason? = null,
    val lastSuccessMs: Long? = null,
    /** Not yet backed up, including [queuedCount]. */
    val pendingCount: Long = 0,
    /** Handed to the Outbox and not yet confirmed, leaving out what the Outbox waits to retry. */
    val queuedCount: Long = 0,
    val uploadingCount: Long = 0,
    val backedUpCount: Long = 0,
    /** Null when some photos' sizes aren't known yet: iOS learns a size only by reading the photo. */
    val backedUpBytes: Long? = 0,
    val remainingBytes: Long? = 0,
    val failures: List<BackupFailure> = emptyList(),
    val failedCount: Long = 0,
    val libraryAccess: LibraryAccess = LibraryAccess.None,
    val photoLocationsWithheld: Boolean = false,
    val backgroundRefreshOff: Boolean = false,
    val working: List<BackupWork> = emptyList(),
) {
    private val preparingCount: Long
        get() = working.count { it.stage == BackupWorkStage.Preparing }.toLong()

    /** Nothing has started on these yet. */
    val waitingCount: Long
        get() = (pendingCount - queuedCount - preparingCount).coerceAtLeast(0)

    /** Started: preparing counts too, so a transcode doesn't look like nothing is happening. */
    val inProgressCount: Long
        get() = uploadingCount + queuedCount + preparingCount

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
    status.pauseReason == BackupPauseReason.StorageFull || status.pauseReason == BackupPauseReason.NoLibraryAccess ||
        status.pauseReason == BackupPauseReason.PhoneStorageLow || status.pauseReason == BackupPauseReason.NeedsSignIn ->
        BackupIndicator.NeedsAttention
    status.isRunning || status.uploadingCount > 0 -> BackupIndicator.Working
    status.failedCount > 0 -> BackupIndicator.NeedsAttention
    status.pauseReason == BackupPauseReason.BackupOff || status.pauseReason == BackupPauseReason.SignedOut -> null
    status.pauseReason != null -> BackupIndicator.Paused
    // The Outbox carries on with what it was handed after the run ends.
    status.working.isNotEmpty() -> BackupIndicator.Working
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
    /** Free space on the volume exports and encrypted copies are written to; null when the OS won't say. */
    val freeStorageBytes: Long? = null,
    /** iOS `serious` or worse, Android `SEVERE` or worse: the OS is already throttling. */
    val isOverheated: Boolean = false,
)

/**
 * The device facts backup depends on.
 *
 * Contract for every implementation: [state] answers for the moment of the call and [libraryAccess]
 * never raises the system permission dialog. Neither one suspends for longer than a few seconds.
 * [state] fills in `freeStorageBytes` and `isOverheated` on every platform that backs up; it's
 * polled every few seconds during a run, so both must be cheap.
 */
interface BackupDeviceProbe {
    suspend fun state(): DeviceState

    fun libraryAccess(): LibraryAccess

    /** True when the OS hands backup photos with their GPS removed; Android 10+ without `ACCESS_MEDIA_LOCATION`. */
    fun photoLocationsWithheld(): Boolean = false

    /** True when the OS won't launch the app in the background for backup. */
    suspend fun isBackgroundRefreshOff(): Boolean = false
}

/**
 * [interactive]: the user has the app open, which lifts the battery conditions but not Wi-Fi only.
 * A full or hot phone pauses every run: opening the app frees no space and cools nothing.
 */
fun backupPauseReason(settings: BackupSettings, state: DeviceState, interactive: Boolean): BackupPauseReason? = when {
    state.isPhoneStorageLow -> BackupPauseReason.PhoneStorageLow
    state.isOverheated -> BackupPauseReason.TooHot
    !state.hasNetwork -> BackupPauseReason.NoNetwork
    settings.wifiOnly && !state.isUnmetered -> BackupPauseReason.WaitingForWifi
    interactive -> null
    settings.chargingOnly && !state.isCharging -> BackupPauseReason.WaitingForCharging
    state.isLowPowerMode && !state.isCharging -> BackupPauseReason.LowPowerMode
    state.isBatteryLow && !state.isCharging -> BackupPauseReason.LowBattery
    else -> null
}

// Room for one large export and its encrypted copy. A guess, until a real run on a full phone says otherwise.
private const val MIN_FREE_STORAGE_BYTES = 1L * 1024 * 1024 * 1024

val DeviceState.isPhoneStorageLow: Boolean
    get() = freeStorageBytes != null && freeStorageBytes < MIN_FREE_STORAGE_BYTES

fun networkAllowsVideos(settings: BackupSettings, state: DeviceState): Boolean =
    state.isUnmetered || settings.videosOnCellular

fun backupVideosAllowed(settings: BackupSettings, state: DeviceState, interactive: Boolean): Boolean {
    return networkAllowsVideos(settings, state) && (interactive || state.isCharging)
}

// About a minute of 1080p from a phone camera: the transcode a background run can finish before the OS stops it.
const val MAX_BACKGROUND_VIDEO_BYTES = 100L * 1024 * 1024

/** The largest video, by source size, a run may start; null for no videos. */
fun largestVideoBytes(settings: BackupSettings, state: DeviceState, interactive: Boolean): Long? = when {
    !backupVideosAllowed(settings, state, interactive) -> null
    interactive -> Long.MAX_VALUE
    else -> MAX_BACKGROUND_VIDEO_BYTES
}
