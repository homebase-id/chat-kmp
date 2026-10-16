package id.homebase.api.client.invitations

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent

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

    // PROTOTYPE (#1908): create and list any identity's codes on bleeding edge with the hard-coded key

    suspend fun createInvitationCode(owner: String, maxUses: Int): OwnedInvitationCode {
        val response = request(
            {
                httpClient.post(PROTOTYPE_URL) {
                    header(InvitationCodePrototype.API_KEY_HEADER, InvitationCodePrototype.API_KEY)
                    setBody(
                        TextContent(
                            OdinSystemSerializer.serialize(CreateInvitationCodeRequest(owner, maxUses)),
                            ContentType.Application.Json,
                        )
                    )
                }
            },
            secret = null,
        )
        throwForFailure(response)
        return deserialize(response.body)
    }

    suspend fun listInvitationCodes(owner: String): List<OwnedInvitationCode> {
        val response = request(
            {
                httpClient.get(PROTOTYPE_URL) {
                    header(InvitationCodePrototype.API_KEY_HEADER, InvitationCodePrototype.API_KEY)
                    parameter("owner", owner)
                }
            },
            secret = null,
        )
        throwForFailure(response)
        return deserialize(response.body)
    }

    private companion object {
        const val PROTOTYPE_URL =
            "https://${InvitationCodePrototype.BLEEDING_EDGE_PROVISIONING_DOMAIN}/api/registration/v1/invitation-codes"
    }
}
