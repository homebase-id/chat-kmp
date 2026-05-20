package id.homebase.api.client.upgrade

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient

enum class UpgradeStatus { NONE, REQUIRED, RUNNING }

/**
 * Probes the active identity to see whether a server-side data-version upgrade is
 * pending or actively running. While the upgrade hasn't completed, newly added system
 * drives (e.g. the Moments drive) may not yet exist for this tenant.
 */
class IdentityUpgradeProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /**
     * GET /auth/verify-token — checks for upgrade headers.
     * [UpgradeStatus.RUNNING] takes precedence over [UpgradeStatus.REQUIRED] when both are present.
     */
    suspend fun checkUpgradeStatus(): UpgradeStatus {
        val creds = requireCreds()
        val response = plainGet(
            url = apiUrl(creds.domain, "/auth/verify-token"),
            token = creds.accessToken,
        )
        throwForFailure(response)
        return when {
            response.headers.contains(UPGRADE_RUNNING_HEADER) -> UpgradeStatus.RUNNING
            response.headers.contains(UPGRADE_REQUIRED_HEADER) -> UpgradeStatus.REQUIRED
            else -> UpgradeStatus.NONE
        }
    }

    companion object {
        private const val UPGRADE_REQUIRED_HEADER = "X-REQUIRES-UPGRADE"
        private const val UPGRADE_RUNNING_HEADER = "X-UPGRADE-RUNNING"
    }
}
