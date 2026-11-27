package id.homebase.api.client.payload

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient

/** A real PayloadReader over [http], with its own disk cache under [fileOps]. */
internal fun payloadReaderOver(http: HttpClient, credentials: CredentialsManager, fileOps: FileOperationsProvider): PayloadReader {
    val cached = DriveFileProviderCached(http, credentials, fileOps)
    return PayloadReader(
        DriveFileProvider(http, credentials, cached),
        PeerFileByGlobalTransitProvider(http, credentials, DriveFileHttpProvider(http, credentials), cached),
        fileOps,
    )
}
