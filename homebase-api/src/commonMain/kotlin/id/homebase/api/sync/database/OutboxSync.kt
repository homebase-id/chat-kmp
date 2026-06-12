package id.homebase.api.sync.database

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.SendReadReceiptByFileIdsOutboxRequest
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocalAppdataContentOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.drives.upload.cleanupHlsScratch
import id.homebase.api.file.systemFileSystem
import okio.Path.Companion.toPath
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.crypto.toUtf8ByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.*
import kotlin.uuid.Uuid

interface OutboxUploader {
    suspend fun upload(outboxRecord: Outbox, eventBus: EventBus): Unit
}

private fun uploadTypeName(t: Long): String = when (t) {
    DriveOutboxUploader.UploadNewFile -> "UploadNewFile"
    DriveOutboxUploader.UpdateFile -> "UpdateFile"
    DriveOutboxUploader.DeleteFile -> "DeleteFile"
    DriveOutboxUploader.UpdateLocalMetadataTags -> "UpdateLocalMetadataTags"
    DriveOutboxUploader.UpdateLocalMetadataContent -> "UpdateLocalMetadataContent"
    DriveOutboxUploader.SendReadReceiptByFileIds -> "SendReadReceiptByFileIds"
    DriveOutboxUploader.ToggleReaction -> "ToggleReaction"
    DriveOutboxUploader.DeleteFilesByGroupId -> "DeleteFilesByGroupId"
    else -> "Unknown"
}

private fun Outbox.uploadTypeLabel(): String = "${uploadTypeName(uploadType)}($uploadType)"

/**
 * True when enqueuing [incomingUploadType] over an existing pending
 * [existingUploadType] would strand a not-yet-sent create. An `UpdateFile`
 * must not replace a pending `UploadNewFile`: the two share
 * `(driveId, uniqueId)`, so the replace deletes the create that never reached
 * the server, and the update then fails permanently ("Could not find file with
 * uniqueId") — losing the message. The caller must instead re-enqueue the edit
 * AS a create (see `ChatMessageSenderService.updateMessage`). Pure + testable.
 */
internal fun wouldStrandPendingCreate(existingUploadType: Long?, incomingUploadType: Long): Boolean =
    incomingUploadType == DriveOutboxUploader.UpdateFile &&
        existingUploadType == DriveOutboxUploader.UploadNewFile

/**
 * Outcome of [OutboxSync.tryEnqueue]/[OutboxSync.replaceEnqueue]. Replaces the
 * old Boolean, whose `false` collapsed three very different situations —
 * a benign UNIQUE(driveId, uniqueId) collision ("already queued, fine"), the
 * strand guard refusing a downgrade, and a real DB failure ("this request is
 * silently lost") — leaving callers to guess which one happened.
 */
sealed interface EnqueueResult {
    /** The request is durably queued. */
    data object Enqueued : EnqueueResult

    /** A row for this (driveId, uniqueId) is already pending — the UNIQUE
     *  constraint rejected the insert. Usually benign: the queued request will
     *  be sent. Use [OutboxSync.replaceEnqueue] when the new request should
     *  supersede it. */
    data object AlreadyQueued : EnqueueResult

    /** replaceEnqueue refused to replace a pending `UploadNewFile` with an
     *  `UpdateFile` — that would strand the un-sent create (see
     *  [wouldStrandPendingCreate]). Re-enqueue the edit AS a create instead. */
    data object WouldStrandCreate : EnqueueResult

    /** The insert failed for a reason other than the UNIQUE constraint — the
     *  request was NOT queued and will not be sent. */
    data class Failed(val cause: Throwable) : EnqueueResult
}

/** True only for [EnqueueResult.Enqueued] — exactly the old Boolean `true`. */
val EnqueueResult.enqueued: Boolean get() = this == EnqueueResult.Enqueued

class OutboxSync(
    private val databaseManager: DatabaseManager,
    private val uploader: OutboxUploader,
    private val eventBus: EventBus,
    scope: CoroutineScope? = null
) {
    // The threads use the DB & Network, so we use the IO dispatcher
    private val scope = scope ?: supervisedScope("outbox-sync", ioDispatcher)
    @kotlin.concurrent.Volatile
    private var isOnline = false

    fun setOnline(online: Boolean) {
        isOnline = online
    }

    private val MAX_SENDING_THREADS = 3
    private val BASE_DELAY_SECONDS = 30L        // first retry after 30s
    private val MAX_DELAY_SECONDS = 14400L      // 4 hours cap
    private val MAX_RETRIES = 20                // ~48 hours total
    private val semaphore = Semaphore(MAX_SENDING_THREADS)
    private val activeThreads = atomic(0)

    private val totalSent = atomic(0)
    private val counterMutex = Mutex()

    private val checkoutMutex = Mutex()

    // The send() function spawns a thread when it acquires the lock.
    // Then send() returns true if it begins processing in a thread, and false if
    // another thread is already processing.
    // Then the call immediately knows if a worker thread has been spawned.
    //
    suspend fun send(): Boolean {
        if (!isOnline) {
            Logger.d("OutboxSync: send() skipped — offline")
            return false
        }
        if (!semaphore.tryAcquire()) {
            return false
        }

        scope.launch {
            try {
                counterMutex.withLock {
                    if (activeThreads.incrementAndGet() == 1) {
                        eventBus.emit(BackendEvent.OutboxEvent.Started)
                    }
                }
                outboxSend()
            } finally {
                // After loop, check if this is the final thread
                var nextSend: UnixTimeUtc? = null
                try {
                    counterMutex.withLock {
                        if (activeThreads.decrementAndGet() == 0) {
                            val n = totalSent.getAndSet(0)
                            nextSend = databaseManager.outbox.nextScheduled()
                            eventBus.emit(BackendEvent.OutboxEvent.Completed(n))
                        }
                    }
                } finally {
                    semaphore.release()
                }
                if (nextSend != null) {
                    val delay = nextSend!!.milliseconds - UnixTimeUtc.now().milliseconds
                    delay(delay) // Put the thread to sleep
                    send()
                }
            }
        }
        return true
    }

    private suspend fun outboxSend() {
        while (true) {
            Logger.i("Popping Outbox")

//            val outboxRecord = databaseManager.outbox.checkout()
            val outboxRecord = checkoutMutex.withLock {
                databaseManager.outbox.checkout()
            }

            if (outboxRecord == null) {
                Logger.i("No more items in outbox")
                break;
            }

            // Doesn't matter if it's not fully thread safe, semaphore ultimate guard
            if (activeThreads.value < MAX_SENDING_THREADS)
                this.send() // Try to spawn a thread for parallel outbox processing

            try {
                // We sent the item, send an event
                eventBus.emit(
                    BackendEvent.OutboxEvent.ItemStarted(
                        outboxRecord.driveId,
                        outboxRecord.uniqueId
                    )
                )
                Logger.i("OutboxSync: sending uniqueId=${outboxRecord.uniqueId} uploadType=${outboxRecord.uploadTypeLabel()} driveId=${outboxRecord.driveId} attempt=${outboxRecord.checkOutCount + 1}")

                uploader.upload(outboxRecord, eventBus)

                // if successful we remove it from the database
                databaseManager.outbox.deleteByRowId(outboxRecord.rowId)
                Logger.i("OutboxSync: completed uniqueId=${outboxRecord.uniqueId} uploadType=${outboxRecord.uploadTypeLabel()}")

                // We sent the item, send an event
                eventBus.emit(
                    BackendEvent.OutboxEvent.ItemCompleted(
                        outboxRecord.driveId,
                        outboxRecord.uniqueId
                    )
                )
                totalSent.incrementAndGet()
            } catch (e: CancellationException) {
                // Worker scope cancelled (logout, shutdown) — not an upload
                // failure. Don't classify, don't checkInFailed, don't emit
                // failure events: the row stays checked out and the next
                // start's clearCheckedOut recovers it, exactly like an app
                // kill. Without this rethrow the catch below would record a
                // bogus failed attempt (it only avoids that today because its
                // first suspension point happens to rethrow cancellation).
                throw e
            } catch (e: Exception) {
                val attempts = outboxRecord.checkOutCount + 1

                val permanentReason = classifyPermanentFailure(e)
                if (attempts >= MAX_RETRIES || permanentReason != null) {
                    val reason = if (permanentReason != null) {
                        "permanent failure ($permanentReason)"
                    } else {
                        "after $attempts failed attempts"
                    }
                    Logger.e(
                        "OutboxSync: DROPPING uniqueId=${outboxRecord.uniqueId} " +
                                "uploadType=${outboxRecord.uploadTypeLabel()} $reason. " +
                                "Last error: ${e.message}",
                        e
                    )
                    databaseManager.outbox.deleteByRowId(outboxRecord.rowId)

                    // Drop branch: the upload exhausted retries or hit a permanent
                    // error, so nothing else will clean up the request's payload
                    // temps. Reach into the serialized request, pull out the
                    // payloads, and reap both the per-file temps (resolved_*.mp4
                    // etc.) AND any hls_<uuid>/ parent dir. Wrapped in
                    // runCatching — cleanup failure must never affect drop
                    // semantics.
                    runCatching {
                        cleanupPayloadsForDroppedRow(outboxRecord)
                    }.onFailure {
                        Logger.w("OutboxSync: payload cleanup failed for dropped uniqueId=${outboxRecord.uniqueId}", it)
                    }

                    eventBus.emit(
                        BackendEvent.OutboxEvent.OutboxItemDropped(
                            outboxRecord.driveId,
                            outboxRecord.uniqueId,
                            attempts.toInt(),
                            reason = permanentReason ?: "retries exhausted ($attempts)",
                        )
                    )
                    continue
                }

                // Exponential backoff: 30s, 60s, 2m, 4m, 8m, 16m, 32m, 64m, 2h, 4h, 4h, ...
                val n = minOf(BASE_DELAY_SECONDS * (1L shl minOf(outboxRecord.checkOutCount.toInt(), 30)), MAX_DELAY_SECONDS)
                Logger.w(
                    "Failed upload for ${outboxRecord.uniqueId} uploadType=${outboxRecord.uploadTypeLabel()}, retry in $n seconds (attempt $attempts/$MAX_RETRIES)",
                    e
                )

                // nextRunTime is a MILLISECONDS epoch — checkout/nextScheduled
                // compare it against UnixTimeUtc.now().milliseconds. Storing
                // `.seconds` here (the pre-fix bug) made every backoff deadline
                // a ~1970s-era value, so failed rows were immediately
                // re-eligible and the 30s→4h backoff was never enforced.
                databaseManager.outbox.checkInFailed(
                    outboxRecord.checkOutStamp!!,
                    UnixTimeUtc.now().addSeconds(n).milliseconds
                )

                eventBus.emit(
                    BackendEvent.OutboxEvent.ItemFailed(
                        outboxRecord.driveId,
                        outboxRecord.uniqueId
                    )
                )

                eventBus.emit(BackendEvent.OutboxEvent.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Fire-and-forget send kick after a successful enqueue: the caller (e.g.
     * the chat Send button) must not wait on outbox worker startup. send() is
     * non-blocking today, but we launch it on the outbox's own scope so future
     * changes to send() can't leak back into the caller's suspension chain.
     */
    private fun kickIfEnqueued(result: EnqueueResult, sendNow: Boolean): EnqueueResult {
        if (sendNow && result.enqueued) {
            scope.launch { send() }
        }
        return result
    }

    public suspend fun tryEnqueue(
        request: DeleteFilesByGroupIdOutboxRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): EnqueueResult = kickIfEnqueued(
        tryEnqueue(
            driveId = request.driveId,
            uniqueId = Uuid.random(),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.DeleteFilesByGroupId,
            json = OdinSystemSerializer.serialize(request)
        ),
        sendNow,
    )

    public suspend fun tryEnqueue(
        request: DeleteLocalFilesByFileIdRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): EnqueueResult = kickIfEnqueued(
        tryEnqueue(
            driveId = request.driveId,
            uniqueId = Uuid.random(), //random because our request is a list of files
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.DeleteFile,
            json = OdinSystemSerializer.serialize(request)
        ),
        sendNow,
    )

    public suspend fun tryEnqueue(
        request: UpdateFileByUniqueIdRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): EnqueueResult = kickIfEnqueued(
        tryEnqueue(
            driveId = request.driveId,
            uniqueId = request.metadata.appData.uniqueId
                ?: error("unique id required to place in outbox"),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UpdateFile,
            json = OdinSystemSerializer.serialize(request)
        ),
        sendNow,
    )

    public suspend fun replaceEnqueue(
        request: UpdateFileByUniqueIdRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): EnqueueResult = kickIfEnqueued(
        replaceEnqueue(
            driveId = request.driveId,
            uniqueId = request.metadata.appData.uniqueId
                ?: error("unique id required to place in outbox"),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UpdateFile,
            json = OdinSystemSerializer.serialize(request)
        ),
        sendNow,
    )

    public suspend fun tryEnqueue(
        request: UploadFileRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): EnqueueResult = kickIfEnqueued(
        tryEnqueue(
            driveId = request.driveId,
            uniqueId = request.metadata.appData.uniqueId
                ?: error("unique id required to place in outbox"),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = OdinSystemSerializer.serialize(request)
        ),
        sendNow,
    )

    /** Replace any pending row for this message with a fresh create. Used to
     *  coalesce an edit into a still-queued (not-yet-sent) create so the edit
     *  doesn't downgrade it to an UpdateFile and strand it. */
    public suspend fun replaceEnqueue(
        request: UploadFileRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): EnqueueResult = kickIfEnqueued(
        replaceEnqueue(
            driveId = request.driveId,
            uniqueId = request.metadata.appData.uniqueId
                ?: error("unique id required to place in outbox"),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = OdinSystemSerializer.serialize(request)
        ),
        sendNow,
    )

    /** Upload type of the pending row for (driveId, uniqueId), or null if none
     *  is queued. Lets a caller branch on create-vs-update before enqueuing. */
    public suspend fun pendingUploadType(driveId: Uuid, uniqueId: Uuid): Long? =
        databaseManager.outbox.selectByDriveAndUnique(driveId, uniqueId)?.uploadType

    /** The pending row deserialized as an [UploadFileRequest], or null when there
     *  is no pending row or it isn't an `UploadNewFile`. Lets an edit amend a
     *  still-queued create in place — preserving its media payloads — rather than
     *  replacing it with an update the server can't apply. */
    public suspend fun pendingUploadFileRequest(driveId: Uuid, uniqueId: Uuid): UploadFileRequest? {
        val row = databaseManager.outbox.selectByDriveAndUnique(driveId, uniqueId) ?: return null
        if (row.uploadType != DriveOutboxUploader.UploadNewFile) return null
        return OdinSystemSerializer.deserialize<UploadFileRequest>(row.json.decodeToString())
    }

    /** Display-oriented snapshot of the pending row for (driveId, uniqueId) —
     *  what Message Info needs to render "still sending, next attempt in ~N min"
     *  without deserializing the request json. */
    public data class PendingRowSnapshot(
        /** Milliseconds epoch of the next attempt (0 / sentinel = "shortly").
         *  Rows written before the checkInFailed unit fix may carry a
         *  seconds-epoch value — display code must normalize. */
        val nextRunTime: Long,
        val checkOutCount: Long,
        /** True when an upload worker currently holds the row. */
        val isCheckedOut: Boolean,
        val uploadType: Long,
    )

    public suspend fun pendingRowSnapshot(driveId: Uuid, uniqueId: Uuid): PendingRowSnapshot? {
        val row = databaseManager.outbox.selectByDriveAndUnique(driveId, uniqueId) ?: return null
        return PendingRowSnapshot(
            nextRunTime = row.nextRunTime,
            checkOutCount = row.checkOutCount,
            isCheckedOut = row.checkOutStamp != null,
            uploadType = row.uploadType,
        )
    }

    /**
     * Force a queued (backed-off) row to be eligible immediately and kick the
     * send loop — the "Try now" button. Returns false when nothing changed:
     * the row is gone (already sent/dropped) or currently checked out (the
     * upload is literally running — a harmless no-op either way). A row with
     * an unresolved dependency gets its time reset but still waits for the
     * dependency to drain (the checkout SQL's NOT EXISTS guard).
     */
    public suspend fun runNow(driveId: Uuid, uniqueId: Uuid): Boolean {
        val changed = databaseManager.outbox.setNextRunTime(driveId, uniqueId, 0L)
        if (changed > 0) {
            scope.launch { send() }
        }
        return changed > 0
    }

    public suspend fun tryEnqueue(
        request: UpdateLocalMetadataTagsOutboxRequest,
        driveId: Uuid,
        uniqueId: Uuid,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): EnqueueResult = kickIfEnqueued(
        tryEnqueue(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UpdateLocalMetadataTags,
            json = OdinSystemSerializer.serialize(request)
        ),
        sendNow,
    )

    public suspend fun tryEnqueue(
        request: UpdateLocalAppdataContentOutboxRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): EnqueueResult {
        Logger.d(tag = "MarkAsRead") {
            "OutboxSync.tryEnqueue(UpdateLocalAppdataContent): drive=${request.driveId} fileId=${request.fileId} hasIv=${request.iv != null} sendNow=$sendNow"
        }
        val result = tryEnqueue(
            driveId = request.driveId,
            uniqueId = request.fileId,
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UpdateLocalMetadataContent,
            json = OdinSystemSerializer.serialize(request)
        )
        Logger.d(tag = "MarkAsRead") {
            "OutboxSync.tryEnqueue(UpdateLocalAppdataContent): result=$result drive=${request.driveId} fileId=${request.fileId}"
        }
        return kickIfEnqueued(result, sendNow)
    }

    public suspend fun tryEnqueue(
        request: ToggleReactionOutboxRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): EnqueueResult = kickIfEnqueued(
        tryEnqueue(
            driveId = request.driveId,
            uniqueId = Uuid.random(),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.ToggleReaction,
            json = OdinSystemSerializer.serialize(request)
        ),
        sendNow,
    )

    public suspend fun tryEnqueue(
        request: SendReadReceiptByFileIdsOutboxRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): EnqueueResult {
        Logger.d(tag = "MarkAsRead") {
            "OutboxSync.tryEnqueue(SendReadReceiptByFileIds): drive=${request.driveId} fileIdsCount=${request.fileIds.size} sendNow=$sendNow"
        }
        val result = tryEnqueue(
            driveId = request.driveId,
            uniqueId = Uuid.random(),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.SendReadReceiptByFileIds,
            json = OdinSystemSerializer.serialize(request)
        )
        Logger.d(tag = "MarkAsRead") {
            "OutboxSync.tryEnqueue(SendReadReceiptByFileIds): result=$result drive=${request.driveId}"
        }
        return kickIfEnqueued(result, sendNow)
    }

    /** Like tryEnqueue but replaces any existing pending item with the same (driveId, uniqueId).
     *  Use when the new request supersedes a stale pending one, e.g. a conversation file update
     *  that was queued while offline and is now outdated. */
    public suspend fun replaceEnqueue(
        driveId: Uuid,
        uniqueId: Uuid,
        dependencyUniqueId: Uuid? = null,
        priority: Long,
        uploadType: Long,
        json: String
    ): EnqueueResult {
        // Defense in depth: never silently downgrade a pending create to an
        // update. Chat's edit path coalesces into a create before reaching here
        // (see ChatMessageSenderService.updateMessage); this guard ensures no
        // current or future caller can strand a not-yet-sent message instead.
        val existing = databaseManager.outbox.selectByDriveAndUnique(driveId, uniqueId)
        if (wouldStrandPendingCreate(existing?.uploadType, uploadType)) {
            Logger.w(
                "OutboxSync: refusing to replace a pending UploadNewFile with an UpdateFile " +
                    "for uniqueId=$uniqueId — would strand the un-sent create."
            )
            return EnqueueResult.WouldStrandCreate
        }
        databaseManager.outbox.deleteBy(driveId, uniqueId)
        return tryEnqueue(driveId, uniqueId, dependencyUniqueId, priority, uploadType, json)
    }

    public suspend fun tryEnqueue(
        driveId: Uuid,
        uniqueId: Uuid,
        dependencyUniqueId: Uuid? = null,
        priority: Long,
        uploadType: Long,
        json: String
    ): EnqueueResult {
        try {
            databaseManager.outbox.insert(
                driveId,
                uniqueId,
                dependencyUniqueId = dependencyUniqueId,
                priority = priority,
                uploadType = uploadType,
                json = json.toUtf8ByteArray(),
                filePaths = null
            )

            // Non-suspending emit: the message is durably queued — listeners are a
            // best-effort side-effect and must not gate the caller. A slow subscriber
            // (doing blocking network IO inside its collect body) can saturate the
            // 11-slot SharedFlow buffer on partial connectivity; parking here would
            // hang the chat Send button.
            val emitted = eventBus.tryEmit(
                BackendEvent.OutboxEvent.ItemEnqueued(driveId, uniqueId)
            )
            if (!emitted) {
                Logger.w("OutboxSync: ItemEnqueued event dropped (EventBus buffer full) uniqueId=$uniqueId")
            }

            return EnqueueResult.Enqueued

        } catch (e: CancellationException) {
            // The caller's coroutine was cancelled — propagate; classifying it
            // as Failed would misreport routine cancellation as a lost request.
            throw e
        } catch (t: Throwable) {
            // Tell the benign UNIQUE(driveId, uniqueId) collision apart from a
            // real DB failure: if a pending row exists for this key, the insert
            // hit the constraint. (Driver-agnostic — constraint exception types
            // differ across JDBC/Android/native.)
            val alreadyQueued = runCatching {
                databaseManager.outbox.selectByDriveAndUnique(driveId, uniqueId) != null
            }.getOrDefault(false)
            if (alreadyQueued) {
                Logger.i("OutboxSync: tryEnqueue found a pending row for uniqueId=$uniqueId — AlreadyQueued")
                return EnqueueResult.AlreadyQueued
            }
            Logger.e("OutboxSync - Failed to Enqueue", t)
            return EnqueueResult.Failed(t)
        }
    }

    /**
     * Decode the dropped row's serialized request just enough to find the
     * payload list, then reap both the per-file temps (resolved_*.mp4 etc.)
     * AND any `hls_<uuid>/` parent dir. Only `UploadNewFile` and `UpdateFile`
     * carry payloads — the other upload types return null/empty and the
     * helpers silently no-op.
     *
     * The success path (DriveUploadProvider.cleanupPayloadTempFiles) does the
     * same per-file delete after a successful upload. This mirror is what
     * keeps a permanently-failed upload (20 retries / terminal error) from
     * leaking its picker-resolved input file. The leak was the largest single
     * contributor to the cacheDir backlog seen on a real device.
     */
    private fun cleanupPayloadsForDroppedRow(outboxRecord: Outbox) {
        val json = outboxRecord.json.decodeToString()
        val payloads = when (outboxRecord.uploadType) {
            DriveOutboxUploader.UploadNewFile ->
                OdinSystemSerializer.deserialize<UploadFileRequest>(json).payloads
            DriveOutboxUploader.UpdateFile ->
                OdinSystemSerializer.deserialize<UpdateFileByUniqueIdRequest>(json).payloads
            else -> null
        } ?: return

        // Per-file delete. Skip content:// SAF URIs (Android share-in / picker
        // sometimes leaves payload.filePath as a content URI we don't own and
        // must not touch).
        for (p in payloads) {
            val path = p.filePath
            if (path.startsWith("content://") || path.startsWith("content:")) continue
            runCatching {
                val okio = path.toPath()
                if (systemFileSystem.exists(okio)) systemFileSystem.delete(okio)
            }.onFailure {
                Logger.w("OutboxSync: drop-cleanup file delete failed for $path", it)
            }
        }

        // Plus the parent hls_<uuid>/ dir if any.
        cleanupHlsScratch(payloads)
    }

    suspend fun clearCheckout(timeoutMs: Long = 10_000) {
        val start = UnixTimeUtc.now().milliseconds

        while (activeThreads.value > 0) {
            if (UnixTimeUtc.now().milliseconds - start > timeoutMs) {
                Logger.w("clearCheckout timed out waiting for outbox to become idle")
                return
            }
            delay(50)
        }

        checkoutMutex.withLock {
            databaseManager.outbox.clearCheckedOut()
        }
    }
}