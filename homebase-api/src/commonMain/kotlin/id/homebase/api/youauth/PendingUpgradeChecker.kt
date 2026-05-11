package id.homebase.api.youauth

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlin.coroutines.cancellation.CancellationException

class PendingUpgradeChecker(
    private val httpClient: HttpClient,
    private val credentialsManager: CredentialsManager,
) {
    suspend fun isUpgradeRequired(): Boolean =
        try {
            val domain = credentialsManager.getActiveDomain()
                ?: return false
            val baseUrl = "https://${domain.domainName}"
            Logger.d(tag = TAG) { "Checking upgrade status for ${domain.domainName}" }
            val response = httpClient.get("$baseUrl/api/apps/v1/auth/verifytoken")
            val required = response.headers["X-REQUIRES-UPGRADE"] != null
            Logger.d(tag = TAG) { "Upgrade required: $required" }
            required
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "Upgrade check failed: ${e.message}" }
            false
        }

    companion object {
        private const val TAG = "PendingUpgradeChecker"
    }
}
