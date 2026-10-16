package id.homebase.api.client.onboarding

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient

/** The identity's onboarding info. The server allows only the owner console and the Chat app. */
class OnboardingProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun getOnboarding(): OnboardingInfo {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/onboarding"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize(response.body)
    }

    /** Marks the onboarding info handled; only the first call changes anything. */
    suspend fun markHandled(): OnboardingInfo {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "/onboarding/handled"),
            token = creds.accessToken,
            jsonBody = "{}",
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize(response.body)
    }
}
