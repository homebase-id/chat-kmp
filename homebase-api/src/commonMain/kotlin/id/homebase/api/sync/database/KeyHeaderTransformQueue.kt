package id.homebase.api.sync.database

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.KeyHeaderTransform
import id.homebase.api.client.drives.SealedKeyHeader
import id.homebase.api.client.drives.SealedKeyHeaders
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.TransformKeyHeadersOutboxRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OutboxSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Sends back every sealed key header files arrive with, re-locked with the drive key: low-priority outbox rows per
 * drive of up to [PER_ROW] files, one request each, where a file queued again keeps only its newest transform.
 */
class KeyHeaderTransformQueue(
    private val outbox: OutboxSync,
    private val databaseManager: DatabaseManager,
    eventBus: EventBus,
    scope: CoroutineScope,
) {
    private val arrived = Channel<SealedKeyHeader>(Channel.UNLIMITED)

    // A file listed again before the server answers would queue the same transform each time.
    private val queued = mutableSetOf<Pair<Uuid, Uuid>>()
    private val queuing = Mutex()

    init {
        SealedKeyHeaders.sink = { arrived.trySend(it) }
        scope.launch {
            for (first in arrived) {
                // A listing hands its files over one by one; a moment's wait fills the rows.
                delay(GATHER)
                val batch = mutableListOf(first)
                while (true) batch += arrived.tryReceive().getOrNull() ?: break
                queuing.withLock { queue(batch) }
            }
        }
        // The outbox is emptied at logout; what this identity queued means nothing to the next.
        scope.launch {
            eventBus.events.filterIsInstance<BackendEvent.SessionEnded>().collect {
                queuing.withLock {
                    while (arrived.tryReceive().isSuccess) Unit
                    queued.clear()
                }
            }
        }
    }

    private suspend fun queue(batch: List<SealedKeyHeader>) {
        batch.filter { (it.transform.fileId to it.transform.versionTag) !in queued }
            .groupBy({ it.driveId }, { it.transform })
            .forEach { (driveId, transforms) ->
                try {
                    transforms.forEach { queued += it.fileId to it.versionTag }
                    // Lost ones are offered again by the file's next read.
                    enqueue(driveId, transforms).forEach { queued -= it.fileId to it.versionTag }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    transforms.forEach { queued -= it.fileId to it.versionTag }
                    Logger.w(e) { "KeyHeaderTransformQueue: transforms for $driveId not queued" }
                }
            }
    }

    /** Returns the transforms that couldn't be stored. Only rows never tried are merged into: rewriting one would reset its backoff. */
    internal suspend fun enqueue(driveId: Uuid, transforms: List<KeyHeaderTransform>, sendNow: Boolean = true): List<KeyHeaderTransform> {
        val merged = LinkedHashMap<Uuid, KeyHeaderTransform>()
        databaseManager.outbox.selectUntriedByDriveAndType(driveId, DriveOutboxUploader.TransformKeyHeaders).forEach { row ->
            if (databaseManager.outbox.deleteByIfNotCheckedOut(driveId, row.uniqueId) > 0) {
                runCatching { OutboxSerializer.decode<TransformKeyHeadersOutboxRequest>(row) }.getOrNull()?.files?.forEach { merged[it.fileId] = it }
            }
        }
        transforms.forEach { merged[it.fileId] = it }
        val lost = mutableListOf<KeyHeaderTransform>()
        merged.values.chunked(PER_ROW).forEach { files ->
            val result = outbox.tryEnqueue(
                driveId = driveId,
                uniqueId = Uuid.random(),
                priority = PRIORITY,
                uploadType = DriveOutboxUploader.TransformKeyHeaders,
                json = OutboxSerializer.serialize(TransformKeyHeadersOutboxRequest(driveId, files)),
            )
            if (!result.enqueued) lost += files
        }
        if (sendNow && lost.size < merged.size) outbox.send()
        return lost
    }

    internal companion object {
        // After everything else: a sealed header still opens, only more slowly, until the server stores the converted one.
        const val PRIORITY = 500L
        const val PER_ROW = 100
        private val GATHER = 1.seconds
    }
}
