package id.homebase.photobase.backup

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal fun testDatabase(driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)): DatabaseManager = DatabaseManager(
    driverProvider = { driver },
    dispatcher = Dispatchers.Unconfined,
    readDispatcher = Dispatchers.Unconfined,
)

internal fun asset(id: String, createdAtMs: Long = 1000, modifiedAtMs: Long = 1000, mediaType: BackupMediaType = BackupMediaType.Image) =
    DeviceAsset(id, mediaType, createdAtMs, modifiedAtMs, sizeBytes = 10)

internal fun fullListing(vararg assets: DeviceAsset, checkpoint: Map<String, String> = mapOf("volume" to "1")) = DeviceChanges(
    assets = assets.toList(),
    deletedIds = emptyList(),
    newCheckpoint = checkpoint,
    currentIds = assets.mapTo(mutableSetOf()) { it.id },
)

internal class FakeSource(var next: DeviceChanges = fullListing()) : DeviceChangeSource {
    val checkpointsSeen = mutableListOf<Map<String, String>>()
    var failure: Exception? = null
    val onlyInCloud = mutableSetOf<String>()

    override suspend fun changes(checkpoint: Map<String, String>, albumIds: Set<String>, wantCurrentIds: Boolean): DeviceChanges {
        checkpointsSeen += checkpoint
        failure?.let { throw it }
        return next
    }

    override suspend fun export(assetId: String, mediaType: BackupMediaType): ExportedAsset {
        if (assetId in onlyInCloud && currentCoroutineContext()[NoCloudDownloads] != null) throw AssetNotOnDeviceException()
        return ExportedAsset(listOf(ExportedPart(ExportedPartRole.Primary, assetId, "image/jpeg", isTemporary = false)), assetId)
    }

    override suspend fun albums(): List<DeviceAlbum> = emptyList()
}

internal class FakeProbe(
    var access: LibraryAccess = LibraryAccess.Full,
    var state: DeviceState = DeviceState(hasNetwork = true, isUnmetered = true, isCharging = true, isBatteryLow = false, isLowPowerMode = false),
    var locationsWithheld: Boolean = false,
) : BackupDeviceProbe {
    override suspend fun state() = state
    override fun libraryAccess() = access
    override fun photoLocationsWithheld() = locationsWithheld
}

/** A hand-off with nothing to guard, as the run passes for a video or a test that has no clip. */
internal suspend fun BackupUploader.handOff(row: DeviceBackupAsset, uniqueId: Uuid) = handOff(row, uniqueId, Unguarded)

/** Answers like the real Outbox would: through the event bus, after [sendTime] of virtual time. */
internal class FakeUploader(
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val sendTime: Duration = 1.seconds,
) : BackupUploader {
    val handedOff = mutableListOf<String>()
    var result: (DeviceBackupAsset) -> HandOffResult = { HandOffResult.Queued }
    var sendsComplete = true
    var sendsFail = false
    var stillSending = false
    /** Answers before handOff returns, as a busy Outbox can for a small photo. */
    var answersBeforeReturning = false
    var kicks = 0
    /** Holds every hand-off until completed, like a long transcode; one that ignores cancel, like a stuck ffmpeg. */
    var handOffGate: CompletableDeferred<Unit>? = null
    var markedBackground = false
    /** Rows whose hand-off goes through the run's guard, as a Live or motion photo's clip transcode does. */
    var transcodes = false
    var beforeTranscode: suspend (DeviceBackupAsset) -> Unit = {}
    var duringTranscode: suspend (DeviceBackupAsset) -> Unit = {}
    /** Rows the guard held back: their clip had stopped the app before. */
    val clipsHeld = mutableListOf<String>()
    override var outlivesProcess = false
    override var maxHandedOff = 6

    override suspend fun handOff(row: DeviceBackupAsset, uniqueId: Uuid, transcoding: TranscodeGuard): HandOffResult {
        handedOff += row.deviceAssetId
        handOffGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
        markedBackground = currentCoroutineContext()[id.homebase.api.video.BackgroundVideoWork.Key] != null
        val outcome = if (transcodes) {
            beforeTranscode(row)
            transcoding { duringTranscode(row); result(row) } ?: HandOffResult.Queued.also { clipsHeld += row.deviceAssetId }
        } else {
            result(row)
        }
        if (outcome == HandOffResult.Queued && sendsComplete && answersBeforeReturning) {
            eventBus.emit(BackendEvent.OutboxEvent.ItemStarted(PhotobaseProtocol.DriveAlias, uniqueId))
            eventBus.emit(BackendEvent.OutboxEvent.ItemCompleted(PhotobaseProtocol.DriveAlias, uniqueId))
            delay(1)
        } else if (outcome == HandOffResult.Queued && sendsComplete) {
            scope.launch {
                delay(sendTime)
                eventBus.emit(BackendEvent.OutboxEvent.ItemStarted(PhotobaseProtocol.DriveAlias, uniqueId))
                if (sendsFail) {
                    eventBus.emit(BackendEvent.OutboxEvent.ItemFailed(PhotobaseProtocol.DriveAlias, uniqueId))
                } else {
                    eventBus.emit(BackendEvent.OutboxEvent.ItemCompleted(PhotobaseProtocol.DriveAlias, uniqueId))
                }
            }
        }
        return outcome
    }

    override suspend fun stillSending(rows: Map<DeviceBackupAsset, Uuid>): Set<Uuid> = if (stillSending) rows.values.toSet() else emptySet()

    override suspend fun kick() {
        kicks++
    }
}
