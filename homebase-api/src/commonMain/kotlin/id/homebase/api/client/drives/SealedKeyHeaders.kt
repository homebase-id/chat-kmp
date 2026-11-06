package id.homebase.api.client.drives

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.crypto.EncryptedKeyHeader
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable
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

/** Every sealed key header a file arrives with, by any route, comes here once opened, to be sent back re-locked. */
object SealedKeyHeaders {
    private val transforms = Channel<Pair<Uuid, KeyHeaderTransform>>(Channel.UNLIMITED)

    /** Drive id and transform, for the outbox; one reader. */
    val waiting: ReceiveChannel<Pair<Uuid, KeyHeaderTransform>> get() = transforms

    /** Never fails the read: a transform that can't be made is left to another device. */
    internal suspend fun opened(driveId: Uuid, fileId: Uuid, versionTag: Uuid?, sealed: EncryptedKeyHeader, opened: KeyHeader) {
        if (versionTag == null) return
        try {
            transforms.trySend(driveId to KeyHeaderTransform(fileId, versionTag, sealed.zkDriveFormOf(opened, driveId)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "no drive-key header for $fileId on $driveId" }
        }
    }

    private const val TAG = "SealedKeyHeaders"
}
