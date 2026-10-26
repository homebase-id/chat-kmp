package id.homebase.api.client.drives.files

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient
import kotlin.uuid.Uuid

/**
 * Moves files a peer sent into the drive itself, the explicit form of what a drive query does
 * inline. Needs nothing but an [HttpClient] and credentials, for the iOS Notification Service
 * Extension.
 */
class DriveInboxProcessor(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /** Processes up to [batchSize] of the oldest items in [driveId]'s transit inbox. */
    suspend fun processInbox(driveId: Uuid, batchSize: Int) {
        ValidationUtil.requireValidUuid(driveId, "driveId")

        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/drives/$driveId/inbox/process"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "batchSize=$batchSize",
        )

        throwForFailure(response)
    }
}
