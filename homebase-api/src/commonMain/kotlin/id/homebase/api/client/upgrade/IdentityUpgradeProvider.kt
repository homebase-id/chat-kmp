package id.homebase.api.client.upgrade

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient

/**
 * Probes the active identity to see whether a server-side data-version upgrade is
 * pending. While the upgrade hasn't run, newly added system drives (e.g. the Moments
 * drive) may not yet exist for this tenant, so callers should surface a "go to the
 * owner console" message instead of trying to mount the drive.
 */
class IdentityUpgradeProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /**
     * GET /auth/verify-token. The endpoint always returns 200 OK; the
     * presence of the `X-REQUIRES-UPGRADE` response header is the signal
     * (the header value itself is not consulted).
     */
    suspend fun isUpgradeRequired(): Boolean {
        val creds = requireCreds()
        val response = plainGet(
            url = apiUrl(creds.domain, "/auth/verify-token"),
            token = creds.accessToken,
        )
        throwForFailure(response)
        return response.headers.contains(UPGRADE_HEADER)
    }

    companion object {
        private const val UPGRADE_HEADER = "X-REQUIRES-UPGRADE"
    }
}
