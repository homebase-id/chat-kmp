@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.client.drives.HomebaseFile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Narrow capability [ContactsProvider] needs to set a contact image: read a contact file header by
 * uniqueId so the file's AES key can be recovered. Backed by `DriveFileProvider.getFileHeaderByUid`
 * in DI — depending on this instead of the concrete provider keeps `ContactsProvider` off the
 * heavier drive-file/caching/platform graph.
 */
fun interface ContactHeaderReader {
    suspend fun getHeaderByUid(driveId: Uuid, uniqueId: Uuid): HomebaseFile?
}
