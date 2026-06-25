package id.homebase.core.ui.screens.location.devices

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.common.time.UnixTimeUtcRange
import id.homebase.api.sync.database.BufferedLocationPoint
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.location.tracking.LocationDeviceId
import id.homebase.core.location.tracking.deviceDisplayName
import id.homebase.core.location.tracking.devicePlatform
import id.homebase.core.ui.screens.location.history.DeviceTrace
import id.homebase.core.ui.screens.location.history.LocationHistoryAssembler
import id.homebase.core.ui.screens.location.model.HOUR_MS
import id.homebase.core.ui.screens.location.model.LOCATION_DEVICE_FILE_TYPE
import id.homebase.core.ui.screens.location.model.LOCATION_TRACK_FILE_TYPE
import id.homebase.core.ui.screens.location.model.LocationDeviceProfile
import id.homebase.core.ui.screens.location.model.LocationTrackCodec
import id.homebase.core.config.locationLabeledDrive
import kotlin.uuid.Uuid

/**
 * One tracking device of this identity, assembled from its profile file
 * (fileType 5611) and the freshest position found in its hour files
 * (plus the local buffer for THIS device, which is fresher than any file).
 *
 * [name] is null for pre-profile devices (hour files exist, profile doesn't) —
 * the UI substitutes a localized fallback with [shortId].
 */
data class LocationDeviceInfo(
    val deviceId: Uuid,
    val name: String?,
    val platform: String,
    val lastFix: BufferedLocationPoint?,
    val isThisDevice: Boolean,
) {
    val shortId: String get() = deviceId.toString().take(4)
}

/**
 * Read-side directory over the Location drive's local index: which devices
 * exist, where they last were, and the day traces both the dashboard preview
 * and the History screen render. Pure reads — viewer devices (desktop/web)
 * use this exactly like trackers do.
 */
class LocationDeviceDirectory(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val deviceId: LocationDeviceId,
    private val driveFileProvider: DriveFileProvider,
) {
    private val logger = Logger.withTag("LocationDeviceDirectory")
    private val driveId = locationLabeledDrive.drive.alias

    suspend fun loadDevices(): List<LocationDeviceInfo> {
        val creds = credentialsManager.getActiveCredentials() ?: return emptyList()
        val identityId = creds.getIdentityId()
        val queryBatch = QueryBatch(identityId)

        val profiles = runCatching {
            queryBatch.queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = 64,
                fileSystemType = FileSystemType.Standard.value,
                filetypesAnyOf = listOf(LOCATION_DEVICE_FILE_TYPE),
            ).records.mapNotNull { file ->
                file.fileMetadata.appData.content?.let { LocationDeviceProfile.decode(it) }
            }
        }.onFailure { logger.e(it) { "profile query failed" } }
            .getOrDefault(emptyList())
            .mapNotNull { p -> runCatching { Uuid.parse(p.deviceId) }.getOrNull()?.let { it to p } }
            .toMap()

        // Freshest position per device: newest hour files first, the first
        // point-bearing file per device wins.
        val lastFixByDevice = mutableMapOf<Uuid, BufferedLocationPoint>()
        runCatching {
            queryBatch.queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = RECENT_HOUR_FILES,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = FileSystemType.Standard.value,
                filetypesAnyOf = listOf(LOCATION_TRACK_FILE_TYPE),
            ).records.forEach { file ->
                val hour = file.fileMetadata.appData.content
                    ?.let { LocationTrackCodec.decodeHeader(it) } ?: return@forEach
                val last = hour.points.maxByOrNull { it.t } ?: return@forEach
                val current = lastFixByDevice[hour.deviceId]
                if (current == null || last.t > current.t) lastFixByDevice[hour.deviceId] = last
            }
        }.onFailure { logger.e(it) { "hour-file query failed" } }

        // This device's buffer is fresher than any uploaded file.
        val ownId = deviceId.value
        runCatching { databaseManager.locationPoint.selectLatest() }.getOrNull()?.let { latest ->
            val current = lastFixByDevice[ownId]
            if (current == null || latest.t > current.t) lastFixByDevice[ownId] = latest
        }

        val allIds = profiles.keys + lastFixByDevice.keys
        return allIds.map { id ->
            val profile = profiles[id]
            LocationDeviceInfo(
                deviceId = id,
                name = profile?.name ?: if (id == ownId) deviceDisplayName() else null,
                platform = profile?.platform ?: if (id == ownId) devicePlatform() else "",
                lastFix = lastFixByDevice[id],
                isThisDevice = id == ownId,
            )
        }.sortedWith(
            compareByDescending<LocationDeviceInfo> { it.isThisDevice }
                .thenByDescending { it.lastFix?.t ?: 0L }
        )
    }

    /**
     * The day's traces (all devices), shared by the History screen and the
     * dashboard's map preview. [dayStartMs]/[dayEndMs] are device-local
     * midnights; the query widens one hour left for boundary-straddling hour
     * files and the assembler filters points back to the exact day.
     */
    suspend fun loadDayTraces(dayStartMs: Long, dayEndMs: Long): List<DeviceTrace> {
        val creds = credentialsManager.getActiveCredentials() ?: return emptyList()
        val result = QueryBatch(creds.getIdentityId()).queryBatchAsync(
            dbm = databaseManager,
            driveId = driveId,
            noOfItems = 24 * 16, // a day of hour files for up to 16 devices
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = FileSystemType.Standard.value,
            filetypesAnyOf = listOf(LOCATION_TRACK_FILE_TYPE),
            userDateSpan = UnixTimeUtcRange(
                start = UnixTimeUtc(dayStartMs - HOUR_MS),
                end = UnixTimeUtc(dayEndMs),
            ),
        )
        val hours = result.records.mapNotNull { file ->
            file.fileMetadata.appData.content?.let { LocationTrackCodec.decodeHeader(it) }
        }
        val bufferPoints = databaseManager.locationPoint.selectByTimeRange(dayStartMs, dayEndMs)
        return LocationHistoryAssembler.assemble(
            hours = hours,
            bufferPoints = bufferPoints,
            bufferDeviceId = deviceId.value,
            dayStartMs = dayStartMs,
            dayEndMs = dayEndMs,
        )
    }

    /**
     * Delete the day's location history: soft-delete (tombstone) the on-drive hour files for
     * `[dayStartMs, dayEndMs)` and clear this device's matching buffer rows. The tombstone keeps
     * `fileType=5610` but flips `fileState` to Deleted, which [loadDayTraces]'s default
     * Active-only QueryBatch filter excludes — so the day renders empty and the deletion
     * propagates without re-syncing back. Because `softDeleteFile` only hits the server, the
     * local index row is also removed (`driveMainIndex.deleteBy`) so the refresh is immediate.
     *
     * Mirrors [loadDayTraces]'s query (one-hour left-widened window for boundary-straddling
     * hour files), but only deletes a file that actually carries a point inside the day — so the
     * prior-day file the widen pulls in (which contributes nothing) is left untouched.
     */
    suspend fun deleteDayTraces(dayStartMs: Long, dayEndMs: Long) {
        val creds = credentialsManager.getActiveCredentials() ?: return
        val identityId = creds.getIdentityId()
        val result = QueryBatch(identityId).queryBatchAsync(
            dbm = databaseManager,
            driveId = driveId,
            noOfItems = 24 * 16,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = FileSystemType.Standard.value,
            filetypesAnyOf = listOf(LOCATION_TRACK_FILE_TYPE),
            userDateSpan = UnixTimeUtcRange(
                start = UnixTimeUtc(dayStartMs - HOUR_MS),
                end = UnixTimeUtc(dayEndMs),
            ),
        )
        for (file in result.records) {
            val hour = file.fileMetadata.appData.content?.let { LocationTrackCodec.decodeHeader(it) } ?: continue
            if (hour.points.none { it.t in dayStartMs until dayEndMs }) continue
            // Personal labeled drive — no peer recipients.
            val deleted = runCatching { driveFileProvider.softDeleteFile(driveId, file.fileId, recipients = null) }
                .onFailure { logger.w(it) { "softDelete hour file ${file.fileId} failed" } }
                .isSuccess
            if (deleted) databaseManager.driveMainIndex.deleteBy(identityId, driveId, file.fileId)
        }
        // Clear this device's buffer rows for the day (flushed copies + not-yet-uploaded).
        databaseManager.locationPoint.deleteByTimeRange(dayStartMs, dayEndMs)
    }

    private companion object {
        // 48 newest hour files ≈ the last 1-2 days across a few devices —
        // enough to find every active device's freshest position cheaply.
        const val RECENT_HOUR_FILES = 48
    }
}
