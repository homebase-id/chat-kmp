package id.homebase.core.ui.screens.location
import id.homebase.upload.UploadService
import id.homebase.upload.UploadOutcome
import id.homebase.upload.MediaUpdateSpec
import id.homebase.upload.MediaUploadSpec

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.BufferedLocationPoint
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.enqueued
import id.homebase.upload.PayloadBundle
import id.homebase.core.config.locationLabeledDrive
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.location.GpsRequestReason
import id.homebase.core.location.tracking.LocationDeviceId
import id.homebase.core.location.tracking.deviceDisplayName
import id.homebase.core.location.tracking.devicePlatform
import id.homebase.core.sync.OptionalDriveActivation
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    private val uploadService: UploadService,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val deviceId: LocationDeviceId,
    private val optionalDriveActivation: OptionalDriveActivation,
    private val scope: CoroutineScope,
    /** Injectable for tests; production reads the wall clock. */
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** OS battery saver on? Wired in DI to DeviceSensors. Defaults false (always upload). */
    private val powerSaveMode: () -> Boolean = { false },
    /** App in the foreground? Wired in DI to the coordinator. Defaults true (always upload). */
    private val isAppForeground: () -> Boolean = { true },
    /**
     * Kick an outbox drain that works even without the websocket — wired in DI to
     * `OutboxSync.send(force = true)` (#987). Called after a flush that actually enqueued.
     * Rationale: every background wake that produces points (push, Android PendingIntent
     * batches into a cold process, iOS SLC relaunch) funnels through [flush], but in those
     * wakes the WS never connects, so the normal enqueue kick declines offline and the
     * hour-file would strand until the next foreground connect. When online this is
     * equivalent to the normal kick. Cost when genuinely offline: one fast-failing POST
     * per flush, bounded by the 60s rate-gate + normal backoff.
     */
    private val drainNow: suspend () -> Unit = {},
) {
    private val logger = Logger.withTag(TAG)
    private val driveId = locationLabeledDrive.drive.alias
    private val buffer get() = databaseManager.locationPoint

    private val flushMutex = Mutex()
    private var lastFlushAttemptMs = 0L
    private var observerStarted = false
    private var deviceProfileEnsured = false
    // One in-flight "wait for the drive to mount, then re-flush" waiter at a time. Set under
    // flushMutex, cleared by the waiter itself. A benign re-arm race (the waiter clears it just as
    // a fresh skip sets it) at worst launches a second idempotent waiter — not worth a lock.
    private var awaitingMount = false

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

    /**
     * Rate-gated flush — the entry point for tickers and background batch wakes.
     *
     * [reason] is log-only (#988): a one-shot capture reason (e.g. PushReceived) promotes the
     * skip-gate lines to Info so a dropped push-capture upload is diagnosable from the log,
     * while the steady-state tracker/ticker path (null) keeps them at debug — this method runs
     * on every persisted point batch, so unconditional Info would spam.
     */
    suspend fun flushIfDue(reason: GpsRequestReason? = null) {
        val now = nowMs()
        if (now - lastFlushAttemptMs < MIN_FLUSH_INTERVAL_MS) {
            logSkip(reason) {
                "flushIfDue(${reason ?: "periodic"}): rate-gated " +
                    "(nextEligibleInMs=${MIN_FLUSH_INTERVAL_MS - (now - lastFlushAttemptMs)})"
            }
            return
        }
        val powerSave = powerSaveMode()
        val foreground = isAppForeground()
        // Battery saver + backgrounded → defer uploads to save power; local capture still persists
        // and drains on the next foreground / saver-off flush (#878 follow-up). Override: never let
        // un-uploaded history sit longer than STALE_BACKLOG_MS — upload at the next chance regardless
        // of saver, so the user's track can't be stranded on-device (and can't reach the 7-day sweep
        // un-uploaded). The backlog query runs only in the would-defer case.
        val hasStaleBacklog = if (powerSave && !foreground) {
            runCatching { buffer.countUnmarkedOlderThan(now - STALE_BACKLOG_MS) }.getOrDefault(0L) > 0L
        } else {
            false
        }
        if (shouldDeferBackgroundFlush(powerSave, foreground, hasStaleBacklog)) {
            logSkip(reason) {
                "Flush deferred: battery saver on + backgrounded, no >${STALE_BACKLOG_MS}ms " +
                    "un-uploaded backlog (reason=${reason ?: "periodic"})"
            }
            return
        }
        flush(reason)
    }

    /** Info for one-shot (push) triggered flushes, debug for the steady-state path (#988). */
    private inline fun logSkip(reason: GpsRequestReason?, crossinline message: () -> String) {
        if (reason != null) logger.i { message() } else logger.d { message() }
    }

    suspend fun flush(reason: GpsRequestReason? = null) {
        flushMutex.withLock {
            val now = nowMs()
            lastFlushAttemptMs = now
            if (!optionalDriveActivation.isActivated(locationLabeledDrive)) {
                logSkip(reason) { "Flush skipped: Location add-on not activated (drive not mounted) reason=${reason ?: "periodic"}" }
                // iOS-only race: a cold background wake (SLC relaunch / emergency-locate push) routes
                // its GPS fix ~300ms BEFORE the drive-mount pipeline finishes, so this flush loses to
                // the mount and — with the foreground-only ticker stopped — nothing retries and the
                // point strands until the next foreground session. (Android's process stays warm, so
                // its drive is already mounted and it never hits this.) Re-flush the instant the drive
                // mounts. Harmless on Android/foreground: isActivated is already true, so we never arm.
                armFlushOnMount(reason)
                return
            }
            if (credentialsManager.getActiveCredentials() == null) {
                // Logged out / not yet logged in: points stay buffered.
                logSkip(reason) { "Flush skipped: no active credentials — points stay buffered reason=${reason ?: "periodic"}" }
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
                logger.d { "Flush: ${hours.size} pending hour(s), ${finalizeHours.size} closed to finalize reason=${reason ?: "periodic"}" }
            }
            var anyEnqueued = false
            for (hourBucket in hours + finalizeHours) {
                runCatching { flushHour(hourBucket * HOUR_MS) }
                    .onSuccess { enqueued -> anyEnqueued = anyEnqueued || enqueued }
                    .onFailure { logger.e(it) { "flushHour($hourBucket) failed" } }
            }
            if (anyEnqueued) {
                // Drain even without the websocket (#987): background wakes (push,
                // PendingIntent batch, SLC relaunch) never connect the WS, so the normal
                // enqueue kick declines offline and the row would strand until the next
                // foreground connect. Equivalent to the normal kick when online.
                runCatching { drainNow() }
                    .onFailure { logger.w(it) { "drainNow failed after flush" } }
            }
            refreshPendingCount()
        }
    }

    /**
     * Re-flush once the Location drive mounts, bounded by [MOUNT_WAIT_MS]. Closes the cold-wake
     * race where a fix's flush ran before the mount pipeline finished (see the call site). The wait
     * is a suspended coroutine on the app scope — it costs nothing while idle and is cancelled with
     * the scope if the wake ends first (iOS suspends the process); the flush re-checks activation and
     * credentials, so a spurious late mount can't upload for a logged-out identity.
     */
    private fun armFlushOnMount(reason: GpsRequestReason?) {
        if (awaitingMount) return
        awaitingMount = true
        scope.launch {
            try {
                val mounted = withTimeoutOrNull(MOUNT_WAIT_MS) {
                    optionalDriveActivation.isActivatedFlow(locationLabeledDrive).first { it }
                }
                if (mounted == true) {
                    logger.i { "Location drive mounted — re-flushing buffered points reason=${reason ?: "periodic"}" }
                    flush(reason)
                }
            } finally {
                awaitingMount = false
            }
        }
    }

    /** @return true when the hour was handed to the outbox (created or update-coalesced). */
    private suspend fun flushHour(hourStartMs: Long): Boolean {
        val points = buffer.selectByTimeRange(hourStartMs, hourStartMs + HOUR_MS)
        if (points.isEmpty()) return false

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
        return enqueued
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
        val outcome = uploadService.upload(
            MediaUploadSpec(
                driveId = driveId,
                uniqueId = uid,
                keyHeader = keyHeader,
                bundle = null,
                metadata = unencryptedMetadata,
                replace = true,
                originalRecipientCount = 0,
                seedCache = false,
            ),
            scope = scope,
        )
        if (outcome is UploadOutcome.Enqueued) {
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
        val (bundle, tempPath) = buildOverflowPayload(hourStartMs, overflowPoints)
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
            // replace=true → replaceEnqueue: successive flushes of the hour coalesce to one row.
            // Location doesn't seed the payload cache.
            val outcome = uploadService.upload(
                MediaUploadSpec(
                    driveId = driveId,
                    uniqueId = uid,
                    keyHeader = keyHeader,
                    bundle = bundle,
                    metadata = unencryptedMetadata,
                    replace = true,
                    originalRecipientCount = 0,
                    seedCache = false,
                    priority = LOCATION_UPLOAD_PRIORITY,
                ),
                scope = scope,
            )
            return outcome is UploadOutcome.Enqueued
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
        val (bundle, tempPath) = buildOverflowPayload(hourStartMs, overflowPoints)
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
            // replace=true → replaceEnqueue keyed on (driveId, uniqueId): successive flushes of
            // the same hour collapse to one pending upload.
            val outcome = uploadService.updateFile(
                MediaUpdateSpec(
                    driveId = driveId,
                    uniqueId = uid,
                    keyHeader = newKeyHeader,
                    bundle = bundle,
                    metadata = unencryptedMetadata,
                    replace = true,
                    priority = LOCATION_UPLOAD_PRIORITY,
                ),
                scope = scope,
            )
            return outcome is UploadOutcome.Enqueued
        } finally {
            tempPath?.let { fileOperationsProvider.deleteTempFile(it) }
        }
    }

    /**
     * Full-resolution payload for overflow hours. Returns (payloads, tempPath);
     * (null, null) for the common header-only case.
     */
    /**
     * Build the plaintext overflow payload bundle for an hour (null for the common header-only
     * case). UploadService encrypts it — shared by the create and update paths so both let the
     * pipeline own encryption.
     */
    private suspend fun buildOverflowPayload(
        hourStartMs: Long,
        points: List<BufferedLocationPoint>?,
    ): Pair<PayloadBundle?, String?> {
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
        return bundle to tempPath
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
        /** Outbox priority for hour-files: 0 outranks chat/moments (1) — an emergency-locate
         *  point must never queue behind a media upload during a short push wake (#987).
         *  Outbox checkout is ORDER BY priority ASC. */
        const val LOCATION_UPLOAD_PRIORITY = 0L

        const val MIN_FLUSH_INTERVAL_MS = 60_000L

        /** Upper bound on the post-skip wait for the drive to mount (~300ms in practice). */
        const val MOUNT_WAIT_MS = 15_000L

        const val RETENTION_MS = 7L * 24 * HOUR_MS
        const val DAY_MS = 24 * HOUR_MS

        /** Un-uploaded backlog older than this forces an upload even in battery saver (24h). */
        const val STALE_BACKLOG_MS = 24L * HOUR_MS
    }
}

/**
 * Whether a flush should be deferred for battery saver. Deferred only while saver is on AND the app
 * is backgrounded AND there's no un-uploaded backlog past the stale threshold — a foregrounded user
 * always uploads, and a >24h backlog always uploads (the safety override). Pure for unit testing.
 */
internal fun shouldDeferBackgroundFlush(
    powerSaveMode: Boolean,
    appForeground: Boolean,
    hasStaleUnuploaded: Boolean,
): Boolean = powerSaveMode && !appForeground && !hasStaleUnuploaded

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
