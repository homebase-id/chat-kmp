@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.client.KeyHeader
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Narrow capability [ContactRepository] needs to read a contact's on-demand `ext_data` payload:
 * fetch and decrypt a named payload from a contact file under the file's [KeyHeader]. Backed by
 * `DriveFileProvider.getPayloadBytesDecrypted` in DI — depending on this instead of the concrete
 * provider keeps the repository off the heavier drive-file/caching/platform graph (mirrors
 * [ContactHeaderReader]).
 *
 * Returns the decrypted UTF-8 JSON bytes, or null when the payload is absent (404).
 */
fun interface ContactPayloadReader {
    suspend fun getPayloadBytes(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
    ): ByteArray?
}
