package id.homebase.api.sync.database

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.SendReadReceiptByTimeOutboxRequest
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataContentOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.crypto.toUtf8ByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.*
import kotlin.uuid.Uuid

interface OutboxUploader {
    suspend fun upload(outboxRecord: Outbox, eventBus: EventBus): Unit
}

class OutboxSync(
    private val databaseManager: DatabaseManager,
    private val uploader: OutboxUploader,
    private val eventBus: EventBus,
    scope: CoroutineScope? = null
) {
    // The threads use the DB & Network, so we use the IO dispatcher
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                Logger.i("OutboxSync: sending uniqueId=${outboxRecord.uniqueId} uploadType=${outboxRecord.uploadType} driveId=${outboxRecord.driveId} attempt=${outboxRecord.checkOutCount + 1}")

                uploader.upload(outboxRecord, eventBus)

                // if successful we remove it from the database
                databaseManager.outbox.deleteByRowId(outboxRecord.rowId)
                Logger.i("OutboxSync: completed uniqueId=${outboxRecord.uniqueId} uploadType=${outboxRecord.uploadType}")

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

                if (attempts >= MAX_RETRIES) {
                    Logger.e(
                        "OutboxSync: DROPPING uniqueId=${outboxRecord.uniqueId} " +
                                "uploadType=${outboxRecord.uploadType} after $attempts failed attempts. " +
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
                    "Failed upload for ${outboxRecord.uniqueId}, retry in $n seconds (attempt $attempts/$MAX_RETRIES)",
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
            send()
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
            send()
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
            send()
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
            send()
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
            send()
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
            send()
        }

        return enqueued
    }

    public suspend fun tryEnqueue(
        request: UpdateLocalMetadataContentOutboxRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        val enqueued = tryEnqueue(
            driveId = request.driveId,
            uniqueId = request.fileId,
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.UpdateLocalMetadataContent,
            json = OdinSystemSerializer.serialize(request)
        )

        if (enqueued && sendNow) {
            send()
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
            send()
        }

        return enqueued
    }

    public suspend fun tryEnqueue(
        request: SendReadReceiptByTimeOutboxRequest,
        priority: Long = 100,
        dependencyUniqueId: Uuid? = null,
        sendNow: Boolean = true
    ): Boolean {
        val enqueued = tryEnqueue(
            driveId = request.driveId,
            uniqueId = Uuid.random(),
            dependencyUniqueId = dependencyUniqueId,
            priority = priority,
            uploadType = DriveOutboxUploader.SendReadReceiptByTime,
            json = OdinSystemSerializer.serialize(request)
        )

        if (enqueued && sendNow) {
            send()
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

            eventBus.emit(
                BackendEvent.OutboxEvent.ItemEnqueued(
                    driveId,
                    uniqueId
                )
            )

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