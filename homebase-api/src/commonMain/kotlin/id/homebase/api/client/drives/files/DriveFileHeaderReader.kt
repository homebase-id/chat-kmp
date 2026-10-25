package id.homebase.api.client.drives.files

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerFile
import io.ktor.client.HttpClient
import kotlin.uuid.Uuid

/**
 * Header-by-uniqueId fetch with nothing but an [HttpClient] and credentials behind it, so a
 * process without the app graph (the iOS Notification Service Extension) can use it as well as
 * [DriveFileProvider].
 */
class DriveFileHeaderReader(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /** The decrypted header, or null when the server answers 404. */
    suspend fun getFileHeaderByUid(driveId: Uuid, uniqueId: Uuid): HomebaseFile? {
        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(uniqueId, "uniqueId")

        val creds = requireCreds()
        val url = apiUrl(creds.domain, "/drives/$driveId/files/by-uid/$uniqueId/header")

        val response = encryptedGet(
            url = url,
            token = creds.accessToken,
            secret = creds.secret
        )

        if (response.status == 404) {
            return null
        }

        throwForFailure(response)

        return deserialize<ServerFile>(response.body).asHomebaseFile(creds.secret)
    }
}
