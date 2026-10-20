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
    /** Whether the identity has a public home page; null when the server does not say */
    val publicWebPresence: Boolean? = null,
)

/** GET /api/v2/plan: the identity's hosting plan, what it gives, and how much of its storage is used. */
@Serializable
private data class IdentityPlan(
    /** The plan the identity gets; null while the server has no plans. */
    val id: String? = null,
    val isDefault: Boolean = false,
    val publicWebPresence: Boolean = false,
    val storage: PlanStorage = PlanStorage(),
)

@Serializable
private data class PlanStorage(
    val usedBytes: Long = 0,
    val quotaBytes: Long? = null,
    val isOverQuota: Boolean = false,
)

class StorageUsageProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /** From GET /api/v2/plan. Null on a server from before hosting plans, which answers 404. */
    suspend fun getUsage(): StorageUsage? {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/plan"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        if (response.status == 404) return null
        throwForFailure(response)
        val plan = deserialize<IdentityPlan>(response.body)
        return StorageUsage(
            plan.id, plan.storage.usedBytes, plan.storage.quotaBytes, plan.storage.isOverQuota, plan.publicWebPresence
        )
    }
}
