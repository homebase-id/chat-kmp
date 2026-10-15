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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess

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
        val response = httpClient.post(prototypeUrl()) {
            header(InvitationCodePrototype.API_KEY_HEADER, InvitationCodePrototype.API_KEY)
            setBody(
                TextContent(
                    OdinSystemSerializer.serialize(CreateInvitationCodeRequest(owner, maxUses)),
                    ContentType.Application.Json,
                )
            )
        }
        return deserialize(requireSuccess(response))
    }

    suspend fun listInvitationCodes(owner: String): List<OwnedInvitationCode> {
        val response = httpClient.get(prototypeUrl()) {
            header(InvitationCodePrototype.API_KEY_HEADER, InvitationCodePrototype.API_KEY)
            parameter("owner", owner)
        }
        return deserialize(requireSuccess(response))
    }

    private fun prototypeUrl() =
        "https://${InvitationCodePrototype.BLEEDING_EDGE_PROVISIONING_DOMAIN}/api/registration/v1/invitation-codes"

    private suspend fun requireSuccess(response: HttpResponse): String {
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "Invitation codes: ${response.status.value} $body" }
        return body
    }
}
