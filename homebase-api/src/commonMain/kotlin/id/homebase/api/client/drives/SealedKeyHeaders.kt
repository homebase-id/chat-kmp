package id.homebase.api.client.drives

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.crypto.EncryptedKeyHeader
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** A sealed key header this device opened, re-locked with the drive key, for the server to store instead. */
@Serializable
data class KeyHeaderTransform(
    val fileId: Uuid,
    /** The version the sealed header was read at: the server refuses the transform once the file has moved on. */
    val versionTag: Uuid,
    val keyHeader: EncryptedKeyHeader,
)

data class SealedKeyHeader(val driveId: Uuid, val transform: KeyHeaderTransform)

/** Every sealed key header a file arrives with, by any route, comes here once opened, to be sent back re-locked. */
object SealedKeyHeaders {
    /** Set by the outbox's queue; until then nothing is kept, and the next read of the file offers it again. */
    @Volatile
    var sink: ((SealedKeyHeader) -> Unit)? = null

    /** Never fails the read: a transform that can't be made is left to another device. */
    internal suspend fun opened(driveId: Uuid, fileId: Uuid, versionTag: Uuid?, sealed: EncryptedKeyHeader, opened: KeyHeader) {
        val sink = sink ?: return
        if (versionTag == null) return
        try {
            sink(SealedKeyHeader(driveId, KeyHeaderTransform(fileId, versionTag, sealed.zkDriveFormOf(opened, driveId))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Every read of the file would fail the same way.
            if (fileId !in unconverted.getAndUpdate { it + fileId }) Logger.w(TAG) { "no drive-key header for $fileId on $driveId: ${e.message}" }
        }
    }

    private val unconverted = atomic(emptySet<Uuid>())

    private const val TAG = "SealedKeyHeaders"
}
