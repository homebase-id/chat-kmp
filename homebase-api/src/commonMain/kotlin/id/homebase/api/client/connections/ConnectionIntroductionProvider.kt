package id.homebase.api.client.connections

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlin.io.encoding.ExperimentalEncodingApi

// ==================== PROVIDER ====================

@OptIn(ExperimentalEncodingApi::class)
class ConnectionIntroductionProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager), IntroductionSender {

    companion object {
        private const val TAG = "ConnectionIntroductionProvider"
    }

    // ------------------------------------------------------------
    // GET /introductions
    // ------------------------------------------------------------

    suspend fun getIntroductions(): List<IntroductionResult> {

        val creds = requireCreds()

        val endpoint = "/connections/introductions"

        val response = encryptedGet(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    // ------------------------------------------------------------
    // POST /introductions
    // ------------------------------------------------------------

    override suspend fun sendIntroductions(
        group: IntroductionGroup
    ): IntroductionResult {

        require(group.recipients.isNotEmpty()) {
            "Recipients cannot be empty"
        }

        val creds = requireCreds()

        val endpoint = "/connections/introductions"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(group),
            secret = creds.secret
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    // ------------------------------------------------------------
    // DELETE /introductions
    // ------------------------------------------------------------

    suspend fun deleteAllIntroductions() {

        val creds = requireCreds()

        val endpoint = "/connections/introductions"

        val response = encryptedDelete(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret
        )

        throwForFailure(response)
    }
}
