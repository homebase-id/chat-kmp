package id.homebase.api.youauth

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient

class ClientRegistrationApi(httpClient: HttpClient, credentialsManager: CredentialsManager) :
    OdinApiProviderBase(httpClient, credentialsManager) {

    /** POST /auth/logout: deletes the sign-in (client registration) these credentials belong to. */
    suspend fun deleteThisClient() {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "/auth/logout"),
            token = creds.accessToken,
            jsonBody = "{}",
            secret = creds.secret,
        )
        throwForFailure(response)
    }
}
