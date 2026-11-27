package id.homebase.core

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.payload.PayloadReader
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient

/** A real PayloadReader over [http] and [cache], reading our drive through [own]. */
internal fun testPayloadReader(
    http: HttpClient,
    credentials: CredentialsManager,
    cache: DriveFileProviderCached,
    fileOps: FileOperationsProvider,
    own: DriveFileProvider = DriveFileProvider(http, credentials, cache),
): PayloadReader = PayloadReader(
    own,
    PeerFileByGlobalTransitProvider(http, credentials, DriveFileHttpProvider(http, credentials), cache),
    fileOps,
)
