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
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.tracking.LocationDeviceId
import id.homebase.core.ui.screens.location.model.HOUR_MS
import id.homebase.core.ui.screens.location.model.LOCATION_POINTS_PAYLOAD_KEY
import id.homebase.core.ui.screens.location.model.LOCATION_TRACK_FILE_TYPE
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
) {
    private val logger = Logger.withTag(TAG)
    private val driveId = locationLabeledDrive.drive.alias
    private val buffer get() = databaseManager.locationPoint

    private val flushMutex = Mutex()
    private var lastFlushAttemptMs = 0L
    private var observerStarted = false

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
            buffer.deleteOlderThan(Clock.System.now().toEpochMilliseconds() - RETENTION_MS)
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
    }

    /** Rate-gated flush — the entry point for tickers and background batch wakes. */
    suspend fun flushIfDue() {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastFlushAttemptMs < MIN_FLUSH_INTERVAL_MS) return
        flush()
    }

    suspend fun flush() {
        flushMutex.withLock {
            lastFlushAttemptMs = Clock.System.now().toEpochMilliseconds()
            if (!preferences.activated.value) return
            if (credentialsManager.getActiveCredentials() == null) {
                // Logged out / not yet logged in: points stay buffered.
                return
            }
            val hours = runCatching { buffer.selectPendingHours() }
                .onFailure { logger.e(it) { "selectPendingHours failed" } }
                .getOrNull() ?: return
            for (hourBucket in hours) {
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
            logger.d {
                "Flushed hour=$hourStartMs points=${points.size} stored=${stored.size} " +
                    "overflowPayload=$overflow mode=${if (existing == null) "create" else "update"}"
            }
        }
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
            val enqueued = outboxSync.replaceEnqueue(request)
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
            val enqueued = outboxSync.replaceEnqueue(request)
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
            when (event) {
                is BackendEvent.OutboxEvent.ItemCompleted -> {
                    if (event.driveId != driveId) return@collect
                    runCatching { buffer.deleteByFlushUid(event.uniqueId) }
                        .onFailure { logger.e(it) { "deleteByFlushUid failed" } }
                    _lastFlushTime.value = Clock.System.now().toEpochMilliseconds()
                    refreshPendingCount()
                }

                is BackendEvent.OutboxEvent.ItemFailed -> {
                    if (event.driveId != driveId) return@collect
                    logger.w { "Hour-file upload failed for ${event.uniqueId} — rows unmarked for retry" }
                    runCatching { buffer.clearFlushMark(event.uniqueId) }
                        .onFailure { logger.e(it) { "clearFlushMark failed" } }
                    refreshPendingCount()
                }

                else -> {}
            }
        }
    }

    private suspend fun refreshPendingCount() {
        _pendingCount.value = runCatching { buffer.countAll() }.getOrDefault(0L)
    }

    private companion object {
        const val MIN_FLUSH_INTERVAL_MS = 60_000L
        const val RETENTION_MS = 7L * 24 * HOUR_MS
    }
}
