package id.homebase.photobase.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal fun testDatabase(): DatabaseManager = DatabaseManager(
    driverProvider = { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) },
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

/** Answers like the real Outbox would: through the event bus, after [sendTime] of virtual time. */
internal class FakeUploader(
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val sendTime: Duration = 1.seconds,
) : BackupUploader {
    val handedOff = mutableListOf<String>()
    var result: (DeviceBackupAsset) -> HandOffResult = { HandOffResult.Queued }
    var sendsComplete = true
    var stillSending = false
    var kicks = 0
    var markedBackground = false
    override var outlivesProcess = false
    override var maxHandedOff = 6

    override suspend fun handOff(row: DeviceBackupAsset, uniqueId: Uuid): HandOffResult {
        handedOff += row.deviceAssetId
        markedBackground = currentCoroutineContext()[id.homebase.api.video.BackgroundVideoWork.Key] != null
        val outcome = result(row)
        if (outcome == HandOffResult.Queued && sendsComplete) {
            scope.launch {
                delay(sendTime)
                eventBus.emit(BackendEvent.OutboxEvent.ItemStarted(PhotobaseProtocol.DriveAlias, uniqueId))
                eventBus.emit(BackendEvent.OutboxEvent.ItemCompleted(PhotobaseProtocol.DriveAlias, uniqueId))
            }
        }
        return outcome
    }

    override suspend fun stillSending(rows: Map<DeviceBackupAsset, Uuid>): Set<Uuid> = if (stillSending) rows.values.toSet() else emptySet()

    override suspend fun kick() {
        kicks++
    }
}
