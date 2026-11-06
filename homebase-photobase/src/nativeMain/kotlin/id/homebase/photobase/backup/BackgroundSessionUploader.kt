package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.platform.BackgroundUploadBridgeHolder
import id.homebase.api.platform.BackgroundUploadResults
import id.homebase.api.sync.database.DeviceBackupAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.timeIntervalSince1970
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private val ABANDONED_BODY_AGE = 7.days

/**
 * The whole encrypted multipart body goes to a file, and the system's URLSession takes it from there,
 * even after the app is suspended. Handing over from the open app matters: iOS treats an upload started
 * in the background as low priority. Videos stay on the Outbox, because their HLS body is a directory.
 */
class BackgroundSessionUploader(
    private val outbox: OutboxBackupUploader,
    private val requests: BackupRequestBuilder,
    private val source: DeviceChangeSource,
    private val uploadProvider: DriveUploadProvider,
    private val fileOps: FileOperationsProvider,
    private val wifiOnly: () -> Boolean,
) : BackupUploader {

    override val outlivesProcess: Boolean get() = BackgroundUploadBridgeHolder.bridge != null

    override val maxHandedOff: Int get() = SESSION_BATCH

    override suspend fun handOff(row: DeviceBackupAsset, uniqueId: Uuid): HandOffResult {
        val mediaType = BackupMediaType.ofCode(row.mediaType)
        val bridge = BackgroundUploadBridgeHolder.bridge
        if (bridge == null || mediaType == BackupMediaType.Video) return outbox.handOff(row, uniqueId)

        val exported = source.export(row.deviceAssetId, mediaType)
        try {
            val request = requests.build(row, uniqueId, mediaType, exported) ?: return HandOffResult.ChangedUnderUs
            val bodyPath = bodyPath(fileOps, uniqueId)
            val prepared = try {
                timed(BackupStage.UploadBody) { uploadProvider.writeUploadBody(request, bodyPath) }
            } catch (e: Throwable) {
                runCatching { fileOps.deleteTempFile(bodyPath) }
                throw e
            } finally {
                request.payloads.forEach { runCatching { fileOps.deleteTempFile(it.filePath) } }
            }
            val taskId = timed(BackupStage.Enqueue) {
                bridge.enqueue(uniqueId.toString(), prepared.url, prepared.bearerToken, prepared.contentType, bodyPath, wifiOnly())
            }
            if (taskId == null) {
                runCatching { fileOps.deleteTempFile(bodyPath) }
                throw BackupEnqueueFailedException("the background session refused the upload")
            }
            return HandOffResult.Sending(taskId)
        } finally {
            exported.deleteTemporaryParts(fileOps)
        }
    }

    override suspend fun stillSending(rows: Map<DeviceBackupAsset, Uuid>): Set<Uuid> {
        val (inSession, inOutbox) = rows.entries.partition { it.key.uploadTaskId != null }
        val active = if (inSession.isEmpty()) emptySet() else activeUploadKeys()
        return outbox.stillSending(inOutbox.associate { it.key to it.value }) +
            inSession.map { it.value }.filter { it.toString() in active }
    }

    private suspend fun activeUploadKeys(): Set<String> {
        val bridge = BackgroundUploadBridgeHolder.bridge ?: return emptySet()
        return suspendCancellableCoroutine { continuation ->
            bridge.activeUploadKeys { keys -> continuation.resume(keys.toSet()) }
        }
    }

    override suspend fun kick() = outbox.kick()

    private companion object {
        const val SESSION_BATCH = 50
    }
}

// A subdirectory, because the Outbox's idle reap deletes loose staging files after 24 hours, and a low-priority upload can wait longer.
private fun bodyDirectory(fileOps: FileOperationsProvider) = "${fileOps.getOutboxStagingDirectory()}/photo-bodies"

/** Keyed by uniqueId, in the durable staging dir: it has to survive the process, like an Outbox payload. */
private fun bodyPath(fileOps: FileOperationsProvider, uniqueId: Uuid) = "${bodyDirectory(fileOps)}/$uniqueId"

/** Clears bodies whose upload never reported back; the age floor spares one written just before it's enqueued. */
private fun deleteAbandonedBodies(fileOps: FileOperationsProvider) {
    val bridge = BackgroundUploadBridgeHolder.bridge ?: return
    val directory = bodyDirectory(fileOps)
    bridge.activeUploadKeys { active ->
        val files = NSFileManager.defaultManager
        val cutoff = NSDate().timeIntervalSince1970 - ABANDONED_BODY_AGE.inWholeSeconds
        files.contentsOfDirectoryAtPath(directory, null).orEmpty().filterIsInstance<String>().filter { it !in active }.forEach { name ->
            val path = "$directory/$name"
            val modified = files.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate
            if (modified != null && modified.timeIntervalSince1970 < cutoff) files.removeItemAtPath(path, null)
        }
    }
}

/** Results reach the ledger whenever they arrive; a relaunch delivers ones from before the kill. */
fun listenForBackgroundUploadResults(
    ledger: BackupLedger,
    fileOps: FileOperationsProvider,
    scope: CoroutineScope,
) {
    deleteAbandonedBodies(fileOps)
    BackgroundUploadResults.setHandler { result ->
        scope.launch {
            val uniqueId = Uuid.parseOrNull(result.uploadKey) ?: return@launch
            if (backgroundUploadLanded(result.status, result.body)) {
                ledger.onOutboxCompleted(uniqueId)
            } else {
                Logger.w(tag = "BackgroundSessionUploader") { "upload $uniqueId: status=${result.status} error=${result.error}" }
                ledger.onOutboxDropped(uniqueId, result.error ?: "upload refused: ${result.status}")
            }
            runCatching { fileOps.deleteTempFile(bodyPath(fileOps, uniqueId)) }
        }
    }
}
