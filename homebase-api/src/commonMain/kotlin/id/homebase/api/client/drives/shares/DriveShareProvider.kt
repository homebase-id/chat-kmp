package id.homebase.api.client.drives.shares

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.prototype.lib.serialization.Base64ByteArraySerializer
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.serialization.UuidSerializer
import id.homebase.api.youauth.DrivePermissionSet
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * One file shared with one circle on a Selective Share drive. [permissions] holds Read and nothing beyond Read, Write,
 * React and Comment; [lockedFileKey] is the file's key locked with the circle's key, for an encrypted file.
 */
@Serializable
class ShareRequest(
    @Serializable(with = UuidSerializer::class) val fileId: Uuid,
    @Serializable(with = UuidSerializer::class) val circleId: Uuid,
    val permissions: Int,
    val expiresAt: Long? = null,
    @Serializable(with = Base64ByteArraySerializer::class) val lockedFileKey: ByteArray? = null,
)

@Serializable
class ShareRequests(val shares: List<ShareRequest>)

/** A withdrawn share keeps its row with no permissions. */
@Serializable
data class ShareRow(
    @Serializable(with = UuidSerializer::class) val circleId: Uuid,
    val permissions: DrivePermissionSet,
    val expiresAt: Long? = null,
    val created: Long = 0,
    val modified: Long = 0,
)

/** Shares on a Selective Share drive, written only by the app that owns both the drive and the circle. */
class DriveShareProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /** All or none, up to [MAX_SHARES_PER_REQUEST]. */
    suspend fun share(driveId: Uuid, shares: List<ShareRequest>) {
        require(shares.size in 1..MAX_SHARES_PER_REQUEST) { "${shares.size} shares in one request" }
        val creds = requireCreds()
        val response = encryptedPutJson(
            url = apiUrl(creds.domain, "/drives/$driveId/shares"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(ShareRequests(shares)),
            secret = creds.secret,
        )
        throwForFailure(response)
    }

    suspend fun shares(driveId: Uuid, fileId: Uuid): List<ShareRow> {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/drives/$driveId/files/$fileId/shares"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize(response.body)
    }

    suspend fun withdraw(driveId: Uuid, fileId: Uuid, circleId: Uuid) {
        val creds = requireCreds()
        val response = encryptedDelete(
            url = apiUrl(creds.domain, "/drives/$driveId/files/$fileId/shares/$circleId"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        throwForFailure(response)
    }

    companion object {
        const val MAX_SHARES_PER_REQUEST = 100
    }
}
