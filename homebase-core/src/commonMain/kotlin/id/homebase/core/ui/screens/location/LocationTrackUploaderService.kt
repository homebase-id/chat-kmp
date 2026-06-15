package id.homebase.core.ui.screens.location

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.BufferedLocationPoint
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.location.LocationPreferences
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.location.tracking.LocationDeviceId
import id.homebase.core.location.tracking.deviceDisplayName
import id.homebase.core.location.tracking.devicePlatform
import id.homebase.core.ui.screens.location.model.HOUR_MS
import id.homebase.core.ui.screens.location.model.LOCATION_POINTS_PAYLOAD_KEY
import id.homebase.core.ui.screens.location.model.LOCATION_DEVICE_FILE_TYPE
import id.homebase.core.ui.screens.location.model.LOCATION_TRACK_FILE_TYPE
import id.homebase.core.ui.screens.location.model.LocationDeviceProfile
import id.homebase.core.ui.screens.location.model.locationDeviceFileUid
import id.homebase.core.ui.screens.location.model.LocationTrackCodec
import id.homebase.core.ui.screens.location.model.locationHourFileUid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "LocationTrackUploader"

/**
 * Drains the LocationPoint buffer into per-device per-UTC-hour files on the
 * Location drive.
 *
 * Flush algorithm per pending hour (an hour with un-enqueued rows):
 * 1. Re-serialize the FULL hour (marked + unmarked rows) — the hour file is a
 *    whole-document replace, never an append.
 * 2. Header gets the compact (possibly thinned) trace; when thinning dropped
 *    points, a full-resolution encrypted JSON payload rides along (overflow
 *    case — rare under v1 capture cadences, so the server's expensive payload
 *    ingest path is only paid when actually needed).
 * 3. Create vs update: local index first, then one HTTP header probe (fresh
 *    login mid-hour). Updates reuse the existing AES key with a fresh IV
 *    ("AES key must match") and go through replaceEnqueue so multiple flushes
 *    of the same hour coalesce into one pending outbox row.
 * 4. Rows are marked with the file uid on enqueue, deleted on
 *    OutboxEvent.ItemCompleted, and un-marked on ItemFailed for retry.
 */
class LocationTrackUploaderService(
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
    private val payloadEncryptionService: PayloadBundleEncryptionService,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val deviceId: LocationDeviceId,
    private val preferences: LocationPreferences,
    private val scope: CoroutineScope,
    /** Injectable for tests; production reads the wall clock. */
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val logger = Logger.withTag(TAG)
    private val driveId = locationLabeledDrive.drive.alias
    private val buffer get() = databaseManager.locationPoint

    private val flushMutex = Mutex()
    private var lastFlushAttemptMs = 0L
    private var observerStarted = false
    private var deviceProfileEnsured = false

    private val _lastFlushTime = MutableStateFlow<Long?>(null)
    val lastFlushTime: StateFlow<Long?> = _lastFlushTime.asStateFlow()

    private val _pendingCount = MutableStateFlow(0L)
    val pendingCount: StateFlow<Long> = _pendingCount.asStateFlow()

    /**
     * Begin observing outbox completions and run the retention sweep. Called
     * from `onPostAuthenticated` (after reset()); safe to call repeatedly.
     */
    fun start() {
        scope.launch {
            buffer.deleteOlderThan(nowMs() - RETENTION_MS)
            refreshPendingCount()
        }
        if (observerStarted) return
        observerStarted = true
        scope.launch { observeOutbox() }
    }

    fun reset() {
        _lastFlushTime.value = null
        _pendingCount.value = 0
        lastFlushAttemptMs = 0
        deviceProfileEnsured = false
    }

    /** Rate-gated flush — the entry point for tickers and background batch wakes. */
    suspend fun flushIfDue() {
        val now = nowMs()
        if (now - lastFlushAttemptMs < MIN_FLUSH_INTERVAL_MS) return
        flush()
    }

    suspend fun flush() {
        flushMutex.withLock {
            val now = nowMs()
            lastFlushAttemptMs = now
            if (!preferences.activated.value) {
                logger.d { "Flush skipped: Location add-on not activated" }
                return
            }
            if (credentialsManager.getActiveCredentials() == null) {
                // Logged out / not yet logged in: points stay buffered.
                logger.d { "Flush skipped: no active credentials — points stay buffered" }
                return
            }
            val hours = runCatching { buffer.selectPendingHours() }
                .onFailure { logger.e(it) { "selectPendingHours failed" } }
                .getOrNull() ?: return
            // Closed hours that still hold rows but no UN-marked ones (so they
            // never appear in selectPendingHours): their confirmation arrived
            // while the hour was open, so the drain kept the rows. Re-flush each
            // once now that it has closed — the resulting ItemCompleted finally
            // drains them. Without this they'd linger until the 7-day sweep.
            val currentHour = now / HOUR_MS
            val finalizeHours = runCatching { buffer.selectHoursWithRows() }
                .onFailure { logger.e(it) { "selectHoursWithRows failed" } }
                .getOrNull().orEmpty()
                .filter { it < currentHour && it !in hours }

            if (hours.isNotEmpty() || finalizeHours.isNotEmpty()) {
                // Only devices that actually capture points register an identity —
                // viewer devices (desktop/web) never reach this branch.
                runCatching { ensureDeviceProfile() }
                    .onFailure { logger.e(it) { "ensureDeviceProfile failed" } }
                logger.d { "Flush: ${hours.size} pending hour(s), ${finalizeHours.size} closed to finalize" }
            }
            for (hourBucket in hours + finalizeHours) {
                runCatching { flushHour(hourBucket * HOUR_MS) }
                    .onFailure { logger.e(it) { "flushHour($hourBucket) failed" } }
            }
            refreshPendingCount()
        }
    }

    private suspend fun flushHour(hourStartMs: Long) {
        val points = buffer.selectByTimeRange(hourStartMs, hourStartMs + HOUR_MS)
        if (points.isEmpty()) return

        val uid = locationHourFileUid(deviceId.value, hourStartMs)
        val (headerJson, stored) = LocationTrackCodec.encodeHeader(deviceId.value, hourStartMs, points)
        val overflow = stored.size < points.size

        val existing = findExistingFile(uid)
        val enqueued = if (existing == null) {
            enqueueCreate(uid, hourStartMs, headerJson, if (overflow) points else null)
        } else {
            enqueueUpdate(existing, uid, hourStartMs, headerJson, if (overflow) points else null)
        }

        if (enqueued) {
            buffer.markFlushed(uid, hourStartMs, hourStartMs + HOUR_MS)
            logger.i {
                "Flushed hour=$hourStartMs uid=$uid points=${points.size} stored=${stored.size} " +
                    "overflowPayload=$overflow mode=${if (existing == null) "create" else "update"}"
            }
        } else {
            // replaceEnqueue declined (e.g. outbox backpressure): the rows stay
            // UN-marked and a later flush retries the hour. Surfaced loudly
            // because a silent enqueued==false is exactly what hid the iPhone
            // "105 points waiting for hours" stall from the log.
            logger.w {
                "Flush NOT enqueued hour=$hourStartMs uid=$uid points=${points.size} " +
                    "mode=${if (existing == null) "create" else "update"} — rows stay buffered, will retry"
            }
        }
    }

    /**
     * Points captured today (UTC) by this device: the sum of today's hour-file
     * counts (`full` raw count when the header trace was thinned) plus buffered
     * rows not yet serialized into any file. NOT the upload buffer's row count —
     * the buffer drains on upload confirmation, which is exactly why the UI must
     * not derive "points today" from it.
     *
     * Transient overcount window: after an upload FAILS, its rows are unmarked
     * for retry while the optimistic local header still carries their count;
     * the next successful flush reconverges.
     */
    suspend fun countPointsToday(): Int {
        val creds = credentialsManager.getActiveCredentials() ?: return 0
        val now = nowMs()
        val dayStart = now - now % DAY_MS
        val hourUids = (0 until 24)
            .map { dayStart + it * HOUR_MS }
            .filter { it <= now }
            .map { locationHourFileUid(deviceId.value, it) }
        val fromFiles = runCatching {
            databaseManager.driveMainIndex
                .selectHomebaseFilesByUniqueIds(creds.getIdentityId(), driveId, hourUids)
                .sumOf { file ->
                    file.fileMetadata.appData.content
                        ?.let { LocationTrackCodec.decodeHeader(it)?.fullCount } ?: 0
                }
        }.getOrDefault(0)
        val unflushed = runCatching { buffer.countUnmarkedSince(dayStart) }.getOrDefault(0L)
        return fromFiles + unflushed.toInt()
    }

    /** Local index first; on miss one HTTP probe (fresh login mid-hour, before sync). */
    private suspend fun findExistingFile(uid: Uuid): HomebaseFile? {
        val creds = credentialsManager.getActiveCredentials() ?: return null
        val local = runCatching {
            databaseManager.driveMainIndex
                .selectHomebaseFilesByUniqueIds(creds.getIdentityId(), driveId, listOf(uid))
                .firstOrNull()
        }.getOrNull()
        if (local != null) return local
        return runCatching { driveFileProvider.getFileHeaderByUid(driveId, uid) }
            .onFailure {
                // Offline probe failure: treat as miss. If the file does exist
                // server-side the create fails permanently and the rows are
                // un-marked for retry by the ItemFailed handler — loud but safe.
                logger.w(it) { "Header probe failed for $uid — assuming create" }
            }
            .getOrNull()
    }

    /**
     * Create this device's profile file (fileType 5611) once: gives the
     * anonymous deviceId a human-readable name for Find device / the device
     * list. Auto-only naming v1 — created, never updated.
     */
    private suspend fun ensureDeviceProfile() {
        if (deviceProfileEnsured) return
        val uid = locationDeviceFileUid(deviceId.value)
        if (findExistingFile(uid) != null) {
            deviceProfileEnsured = true
            return
        }
        val profile = LocationDeviceProfile(
            deviceId = deviceId.value.toString(),
            name = deviceDisplayName(),
            platform = devicePlatform(),
        )
        val keyHeader = KeyHeader.newRandom16()
        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = uid,
                content = OdinSystemSerializer.serialize(profile),
                fileType = LOCATION_DEVICE_FILE_TYPE,
            ),
        )
        val request = UploadFileRequest(
            driveId = driveId,
            keyHeader = keyHeader,
            metadata = unencryptedMetadata.encryptContent(keyHeader),
        )
        val enqueued = outboxSync.replaceEnqueue(request).enqueued
        if (enqueued) {
            runCatching {
                optimisticWriter.writeNewFile(
                    driveId = driveId,
                    keyHeader = keyHeader,
                    unecryptedMetadata = unencryptedMetadata,
                    originalRecipientCount = 0,
                    fileSystemType = FileSystemType.Standard,
                )
            }.onFailure { logger.e(it) { "Optimistic write failed (non-fatal) for device profile" } }
            deviceProfileEnsured = true
            logger.i { "Device profile registered: ${profile.name} (${profile.platform})" }
        }
    }

    private suspend fun enqueueCreate(
        uid: Uuid,
        hourStartMs: Long,
        headerJson: String,
        overflowPoints: List<BufferedLocationPoint>?,
    ): Boolean {
        val keyHeader = KeyHeader.newRandom16()
        val (payloads, tempPath) = buildOverflowPayload(uid, hourStartMs, overflowPoints, keyHeader)
        try {
            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = uid,
                    content = headerJson,
                    fileType = LOCATION_TRACK_FILE_TYPE,
                    userDate = hourStartMs,
                ),
            )
            val request = UploadFileRequest(
                driveId = driveId,
                keyHeader = keyHeader,
                metadata = unencryptedMetadata.encryptContent(keyHeader),
                payloads = payloads ?: emptyList(),
            )
            val enqueued = outboxSync.replaceEnqueue(request).enqueued
            if (enqueued) {
                runCatching {
                    optimisticWriter.writeNewFile(
                        driveId = driveId,
                        keyHeader = keyHeader,
                        unecryptedMetadata = unencryptedMetadata,
                        originalRecipientCount = 0,
                        fileSystemType = FileSystemType.Standard,
                    )
                }.onFailure { logger.e(it) { "Optimistic write failed (non-fatal) for $uid" } }
            }
            return enqueued
        } finally {
            tempPath?.let { fileOperationsProvider.deleteTempFile(it) }
        }
    }

    private suspend fun enqueueUpdate(
        existing: HomebaseFile,
        uid: Uuid,
        hourStartMs: Long,
        headerJson: String,
        overflowPoints: List<BufferedLocationPoint>?,
    ): Boolean {
        // The server rejects an update whose AES key differs from the file's
        // ("AES key must match") — reuse it with a fresh IV.
        val newKeyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = existing.keyHeader.aesKey,
        )
        val (payloads, tempPath) = buildOverflowPayload(uid, hourStartMs, overflowPoints, newKeyHeader)
        try {
            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = uid,
                    content = headerJson,
                    fileType = LOCATION_TRACK_FILE_TYPE,
                    userDate = hourStartMs,
                ),
                versionTag = existing.fileMetadata.versionTag,
            )
            val request = UpdateFileByUniqueIdRequest(
                driveId = driveId,
                uniqueId = uid,
                keyHeader = newKeyHeader,
                instructions = FileUpdateInstructionSet(
                    transferIv = ByteArrayUtil.getRndByteArray(16),
                    locale = UpdateLocale.Local,
                    recipients = emptyList(),
                    manifest = UpdateManifest.build(payloads = payloads),
                ),
                metadata = unencryptedMetadata.encryptContent(newKeyHeader),
                payloads = payloads,
            )
            // replaceEnqueue keyed on (driveId, uniqueId): successive flushes of
            // the same hour collapse to one pending upload.
            val enqueued = outboxSync.replaceEnqueue(request).enqueued
            if (enqueued) {
                runCatching {
                    optimisticWriter.writeUpdate(driveId, newKeyHeader, unencryptedMetadata)
                }.onFailure { logger.e(it) { "Optimistic update failed (non-fatal) for $uid" } }
            }
            return enqueued
        } finally {
            tempPath?.let { fileOperationsProvider.deleteTempFile(it) }
        }
    }

    /**
     * Full-resolution payload for overflow hours. Returns (payloads, tempPath);
     * (null, null) for the common header-only case.
     */
    private suspend fun buildOverflowPayload(
        uid: Uuid,
        hourStartMs: Long,
        points: List<BufferedLocationPoint>?,
        keyHeader: KeyHeader,
    ): Pair<List<PayloadFile>?, String?> {
        if (points == null) return null to null
        val json = LocationTrackCodec.encodePayload(deviceId.value, hourStartMs, points)
        val tempPath = fileOperationsProvider.writeBytesToTempFile(
            bytes = json.encodeToByteArray(),
            prefix = "loc_track_",
            suffix = ".json",
        )
        val bundle = PayloadBundle(
            payloads = listOf(
                PayloadFile(
                    key = LOCATION_POINTS_PAYLOAD_KEY,
                    filePath = tempPath,
                    contentType = "application/json",
                )
            ),
            thumbnails = emptyList(),
            previewThumbs = emptyList(),
        )
        val encrypted = payloadEncryptionService.encryptBundle(uid, bundle, keyHeader.aesKey, scope)
        return encrypted.payloads to tempPath
    }

    private suspend fun observeOutbox() {
        eventBus.events.collect { event ->
            if (event !is BackendEvent.OutboxEvent) return@collect
            when (bufferActionFor(event, driveId)) {
                BufferAction.DrainFlushed -> {
                    val uid = (event as BackendEvent.OutboxEvent.ItemCompleted).uniqueId
                    val now = nowMs()
                    // Delete the hour's rows ONLY if the hour has closed; while
                    // still open they stay marked so the next flush re-serializes
                    // the complete hour (otherwise the file is truncated to the
                    // points captured since the last drain — the original bug).
                    logger.i { "Hour-file upload confirmed uid=$uid — draining rows if hour closed" }
                    runCatching { buffer.deleteFlushedIfHourClosed(uid, now) }
                        .onFailure { logger.e(it) { "deleteFlushedIfHourClosed failed" } }
                    _lastFlushTime.value = now
                    refreshPendingCount()
                }

                BufferAction.UnmarkForRetry -> {
                    val (uid, detail) = when (event) {
                        is BackendEvent.OutboxEvent.ItemFailed ->
                            event.uniqueId to "failed — will retry"
                        is BackendEvent.OutboxEvent.OutboxItemDropped ->
                            event.uniqueId to "permanently dropped (${event.reason ?: "no reason"}) — rows re-flush next cycle"
                        else -> return@collect // unreachable by construction of bufferActionFor
                    }
                    logger.w { "Hour-file upload $detail uid=$uid — rows unmarked" }
                    runCatching { buffer.clearFlushMark(uid) }
                        .onFailure { logger.e(it) { "clearFlushMark failed" } }
                    refreshPendingCount()
                }

                BufferAction.None -> {}
            }
        }
    }

    private suspend fun refreshPendingCount() {
        // Unmarked rows only: marked rows are already uploaded and kept just
        // until the hour closes, so they must NOT show as "waiting to upload".
        _pendingCount.value = runCatching { buffer.countUnmarked() }.getOrDefault(0L)
    }

    private companion object {
        const val MIN_FLUSH_INTERVAL_MS = 60_000L
        const val RETENTION_MS = 7L * 24 * HOUR_MS
        const val DAY_MS = 24 * HOUR_MS
    }
}

/** What an outbox event means for the location point buffer. */
internal enum class BufferAction {
    /** Hour file confirmed on the server — delete the flushed rows. */
    DrainFlushed,

    /** Upload didn't land — clear the flush mark so the next cycle re-flushes
     *  the hour from live state (fresh request, fresh key, current points). */
    UnmarkForRetry,

    None,
}

/**
 * Pure event → buffer-action mapping; unit-tested in LocationBufferActionTest.
 *
 * `OutboxItemDropped` must map to [BufferAction.UnmarkForRetry]: a permanent
 * drop (retries exhausted, or a permanent failure such as "AES key must
 * match") emits ONLY this event — no `ItemFailed` — so without this branch the
 * dropped hour's rows stayed flush-marked forever: never re-flushed, never
 * deleted, pending count silently wrong.
 */
internal fun bufferActionFor(
    event: BackendEvent.OutboxEvent,
    locationDriveId: Uuid,
): BufferAction = when (event) {
    is BackendEvent.OutboxEvent.ItemCompleted ->
        if (event.driveId == locationDriveId) BufferAction.DrainFlushed else BufferAction.None

    is BackendEvent.OutboxEvent.ItemFailed ->
        if (event.driveId == locationDriveId) BufferAction.UnmarkForRetry else BufferAction.None

    is BackendEvent.OutboxEvent.OutboxItemDropped ->
        if (event.driveId == locationDriveId) BufferAction.UnmarkForRetry else BufferAction.None

    else -> BufferAction.None
}
