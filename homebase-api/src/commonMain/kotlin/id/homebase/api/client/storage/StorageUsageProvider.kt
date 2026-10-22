package id.homebase.api.client.storage

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

/** The identity's storage against its plan, and who to upgrade it with. */
@Serializable
data class StorageUsage(
    val planId: String? = null,
    val usedBytes: Long = 0,
    /** Null on a server that does not say */
    val quotaBytes: Long? = null,
    val isOverQuota: Boolean = false,
    /** Whether the identity has a public home page; null when the server does not say */
    val publicWebPresence: Boolean? = null,
    /** Who hosts the identity; null when the server names no hosting provider */
    val hostingProvider: HostingProvider? = null,
)

/** The company that hosts the identity, and its page for a bigger plan, ready to open (null when it has none). */
@Serializable
data class HostingProvider(
    val displayName: String = "",
    val upgradeUrl: String? = null,
)

/** GET /api/v2/plan: the identity's plan, what it gives, how much of its storage is used, and its hosting provider. */
@Serializable
internal data class IdentityPlan(
    val id: String? = null,
    val publicWebPresence: Boolean? = null,
    val storage: PlanStorage = PlanStorage(),
    val hostingProvider: HostingProvider? = null,
) {
    fun toStorageUsage() = StorageUsage(
        planId = id,
        usedBytes = storage.usedBytes,
        quotaBytes = storage.quotaBytes,
        isOverQuota = storage.isOverQuota,
        publicWebPresence = publicWebPresence,
        hostingProvider = hostingProvider?.takeIf { it.displayName.isNotBlank() },
    )
}

@Serializable
internal data class PlanStorage(
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
        return deserialize<IdentityPlan>(response.body).toStorageUsage()
    }
}
