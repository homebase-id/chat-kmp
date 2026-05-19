package id.homebase.api.client.upgrade

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient

enum class UpgradeStatus { NONE, REQUIRED, RUNNING }

class IdentityUpgradeProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

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
