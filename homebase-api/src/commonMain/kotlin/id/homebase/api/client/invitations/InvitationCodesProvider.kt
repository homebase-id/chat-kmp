package id.homebase.api.client.invitations

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient

class InvitationCodesProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun getInvitationCodes(): List<InvitationCode> {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/invitation-codes"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize<InvitationCodesResponse>(response.body).codes
    }
}
