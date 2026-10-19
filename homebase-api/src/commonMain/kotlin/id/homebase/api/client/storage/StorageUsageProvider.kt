package id.homebase.api.client.storage

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

/** The identity's storage against its hosting plan. [quotaBytes] is null for an unlimited plan. */
@Serializable
data class StorageUsage(
    val planId: String? = null,
    val usedBytes: Long = 0,
    val quotaBytes: Long? = null,
    val isOverQuota: Boolean = false,
)

class StorageUsageProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /** GET /api/v2/storage/usage. Null on a server without hosting plans, which answers 404. */
    suspend fun getUsage(): StorageUsage? {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/storage/usage"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        if (response.status == 404) return null
        throwForFailure(response)
        return deserialize(response.body)
    }
}
