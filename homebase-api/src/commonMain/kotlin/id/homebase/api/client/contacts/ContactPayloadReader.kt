@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Narrow capability [ContactRepository] needs to read a contact's on-demand payloads (`ext_data`
 * bios / rich text, and the per-app `appextdata` blob): fetch and **decrypt** a named payload of a
 * contact file. Backed in DI by `DriveFileProvider` (read the full file header for the payload's IV
 * + file key, then decrypt through the normal cached payload path). Depending on this thin seam
 * instead of the concrete provider keeps the repository off the heavier drive-file/caching graph and
 * trivially fakeable in tests (mirrors [ContactHeaderReader]).
 *
 * Returns the decrypted bytes, or null when the payload is absent (404) / the file has no such
 * payload / the fetch fails.
 */
fun interface ContactPayloadReader {
    suspend fun fetchPayload(driveId: Uuid, fileId: Uuid, payloadKey: String): ByteArray?
}
