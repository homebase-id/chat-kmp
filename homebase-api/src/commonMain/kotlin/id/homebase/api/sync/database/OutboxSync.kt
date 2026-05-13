package id.homebase.api.sync.database

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.SendReadReceiptByFileIdsOutboxRequest
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocalAppdataContentOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.crypto.toUtf8ByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
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

class OutboxSync(
    private val databaseManager: DatabaseManager,
    private val uploader: OutboxUploader,
    private val eventBus: EventBus,
    scope: CoroutineScope? = null
) {
    // The threads use the DB & Network, so we use the IO dispatcher
    private val scope = scope ?: CoroutineScope(SupervisorJob() + ioDispatcher)
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

    /**
     * Returns true when the upload exception describes a state that won't be
     * fixed by retrying (file not found server-side, missing version tag for
     * an update, etc.). Drops these immediately instead of burning ~48h of
     * exponential-backoff retries.
     */
    private fun isPermanentFailure(e: Throwable): Boolean {
        if (e is NotFoundException) return true
        if (e is ClientException) {
            when (e.errorCode) {
                OdinClientErrorCode.FileNotFound,
                OdinClientErrorCode.MissingVersionTag,
                OdinClientErrorCode.VersionTagMismatch,
                OdinClientErrorCode.CannotOverwriteNonExistentFile,
                OdinClientErrorCode.UnknownId -> return true
                else -> Unit
            }
            // The server sometimes returns 400 with the structured errorCode
            // collapsed to UnhandledScenario but the message text intact.
            // Catch the recurring local-only-placeholder failures we've seen
            // so they don't loop in the outbox. The version-tag check matches
            // both "Missing version tag" and "Mismatching version tag".
            val msg = e.message ?: return false
            if (msg.contains("Could not find file", ignoreCase = true)) return true
            if (msg.contains(Regex("Mis(sing|matching) version tag", RegexOption.IGNORE_CASE))) return true
        }
        return false
    }

    private fun permanentFailureReason(e: Throwable): String = when {
        e is NotFoundException -> "404 NotFound"
        e is ClientException -> "errorCode=${e.errorCode} msg=${e.message}"
        else -> e::class.simpleName ?: "unknown"
    }
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
            } catch (e: Exception) {
                val attempts = outboxRecord.checkOutCount + 1

                if (attempts >= MAX_RETRIES || isPermanentFailure(e)) {
                    val reason = if (attempts >= MAX_RETRIES) {
                        "after $attempts failed attempts"
                    } else {
                        "permanent failure (${permanentFailureReason(e)})"
                    }
                    Logger.e(
                        "OutboxSync: DROPPING uniqueId=${outboxRecord.uniqueId} " +
                                "uploadType=${outboxRecord.uploadTypeLabel()} $reason. " +
                                "Last error: ${e.message}",
                        e
                    )
                    databaseManager.outbox.deleteByRowId(outboxRecord.rowId)
                    eventBus.emit(
                        BackendEvent.OutboxEvent.OutboxItemDropped(
                            outboxRecord.driveId,
                            outboxRecord.uniqueId,
                            attempts.toInt()
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

                databaseManager.outbox.checkInFailed(
                    outboxRecord.checkOutStamp!!,
                    UnixTimeUtc.now().addSeconds(n).seconds
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

    public suspend fun tryEnqueue(
        request: DeleteFilesByGroupIdOutboxRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        val enqueued = tryEnqueue(
            driveId = request.driveId,
            uniqueId = Uuid.random(),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.DeleteFilesByGroupId,
            json = OdinSystemSerializer.serialize(request)
        )

        if (enqueued && sendNow) {
            // Fire-and-forget: the enqueue caller (e.g. chat Send button) must not
            // wait on outbox worker startup. send() is non-blocking today, but we
            // launch it on the outbox's own scope so future changes to send() can't
            // leak back into the caller's suspension chain.
            scope.launch { send() }
        }

        return enqueued
    }

    public suspend fun tryEnqueue(
        request: DeleteLocalFilesByFileIdRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        val enqueued = tryEnqueue(
            driveId = request.driveId,
            uniqueId = Uuid.random(), //random because our request is a list of files
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.DeleteFile,
            json = OdinSystemSerializer.serialize(request)
        )

        if (enqueued && sendNow) {
            // Fire-and-forget: the enqueue caller (e.g. chat Send button) must not
            // wait on outbox worker startup. send() is non-blocking today, but we
            // launch it on the outbox's own scope so future changes to send() can't
            // leak back into the caller's suspension chain.
            scope.launch { send() }
        }

        return enqueued
    }

    public suspend fun tryEnqueue(
        request: UpdateFileByUniqueIdRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        val json = OdinSystemSerializer.serialize(request)
        val enqueued = tryEnqueue(
            driveId = request.driveId,
            uniqueId = request.metadata.appData.uniqueId
                ?: error("unique id required to place in outbox"),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UpdateFile,
            json = json
        )

        if (enqueued && sendNow) {
            // Fire-and-forget: the enqueue caller (e.g. chat Send button) must not
            // wait on outbox worker startup. send() is non-blocking today, but we
            // launch it on the outbox's own scope so future changes to send() can't
            // leak back into the caller's suspension chain.
            scope.launch { send() }
        }

        return enqueued
    }

    public suspend fun replaceEnqueue(
        request: UpdateFileByUniqueIdRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        val json = OdinSystemSerializer.serialize(request)
        val enqueued = replaceEnqueue(
            driveId = request.driveId,
            uniqueId = request.metadata.appData.uniqueId
                ?: error("unique id required to place in outbox"),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UpdateFile,
            json = json
        )

        if (enqueued && sendNow) {
            // Fire-and-forget: the enqueue caller (e.g. chat Send button) must not
            // wait on outbox worker startup. send() is non-blocking today, but we
            // launch it on the outbox's own scope so future changes to send() can't
            // leak back into the caller's suspension chain.
            scope.launch { send() }
        }

        return enqueued
    }

    public suspend fun tryEnqueue(
        request: UploadFileRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        val enqueued = tryEnqueue(
            driveId = request.driveId,
            uniqueId = request.metadata.appData.uniqueId
                ?: error("unique id required to place in outbox"),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = OdinSystemSerializer.serialize(request)
        )

        if (enqueued && sendNow) {
            // Fire-and-forget: the enqueue caller (e.g. chat Send button) must not
            // wait on outbox worker startup. send() is non-blocking today, but we
            // launch it on the outbox's own scope so future changes to send() can't
            // leak back into the caller's suspension chain.
            scope.launch { send() }
        }

        return enqueued
    }

    public suspend fun tryEnqueue(
        request: UpdateLocalMetadataTagsOutboxRequest,
        driveId: Uuid,
        uniqueId: Uuid,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        val enqueued = tryEnqueue(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UpdateLocalMetadataTags,
            json = OdinSystemSerializer.serialize(request)
        )

        if (enqueued && sendNow) {
            // Fire-and-forget: the enqueue caller (e.g. chat Send button) must not
            // wait on outbox worker startup. send() is non-blocking today, but we
            // launch it on the outbox's own scope so future changes to send() can't
            // leak back into the caller's suspension chain.
            scope.launch { send() }
        }

        return enqueued
    }

    public suspend fun tryEnqueue(
        request: UpdateLocalAppdataContentOutboxRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        Logger.d(tag = "MarkAsRead") {
            "OutboxSync.tryEnqueue(UpdateLocalAppdataContent): drive=${request.driveId} fileId=${request.fileId} hasIv=${request.iv != null} sendNow=$sendNow"
        }
        val enqueued = tryEnqueue(
            driveId = request.driveId,
            uniqueId = request.fileId,
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UpdateLocalMetadataContent,
            json = OdinSystemSerializer.serialize(request)
        )
        Logger.d(tag = "MarkAsRead") {
            "OutboxSync.tryEnqueue(UpdateLocalAppdataContent): enqueued=$enqueued drive=${request.driveId} fileId=${request.fileId}"
        }

        if (enqueued && sendNow) {
            // Fire-and-forget: the enqueue caller (e.g. chat Send button) must not
            // wait on outbox worker startup. send() is non-blocking today, but we
            // launch it on the outbox's own scope so future changes to send() can't
            // leak back into the caller's suspension chain.
            scope.launch { send() }
        }

        return enqueued
    }

    public suspend fun tryEnqueue(
        request: ToggleReactionOutboxRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        val enqueued = tryEnqueue(
            driveId = request.driveId,
            uniqueId = Uuid.random(),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.ToggleReaction,
            json = OdinSystemSerializer.serialize(request)
        )

        if (enqueued && sendNow) {
            // Fire-and-forget: the enqueue caller (e.g. chat Send button) must not
            // wait on outbox worker startup. send() is non-blocking today, but we
            // launch it on the outbox's own scope so future changes to send() can't
            // leak back into the caller's suspension chain.
            scope.launch { send() }
        }

        return enqueued
    }

    public suspend fun tryEnqueue(
        request: SendReadReceiptByFileIdsOutboxRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        Logger.d(tag = "MarkAsRead") {
            "OutboxSync.tryEnqueue(SendReadReceiptByFileIds): drive=${request.driveId} fileIdsCount=${request.fileIds.size} sendNow=$sendNow"
        }
        val enqueued = tryEnqueue(
            driveId = request.driveId,
            uniqueId = Uuid.random(),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.SendReadReceiptByFileIds,
            json = OdinSystemSerializer.serialize(request)
        )
        Logger.d(tag = "MarkAsRead") {
            "OutboxSync.tryEnqueue(SendReadReceiptByFileIds): enqueued=$enqueued drive=${request.driveId}"
        }

        if (enqueued && sendNow) {
            // Fire-and-forget: the enqueue caller (e.g. chat Send button) must not
            // wait on outbox worker startup. send() is non-blocking today, but we
            // launch it on the outbox's own scope so future changes to send() can't
            // leak back into the caller's suspension chain.
            scope.launch { send() }
        }

        return enqueued
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
    ): Boolean {
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
    ): Boolean {
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

            return true

        } catch (t: Throwable) {
            Logger.e("OutboxSync - Failed to Enqueue", t)
        }

        return false

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