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
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "ConnectionIntroductionProvider"
    }

    // ------------------------------------------------------------
    // GET /introductions
    // ------------------------------------------------------------

    suspend fun getIntroductions(): List<IntroductionResponse> {

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

    suspend fun sendIntroductions(
        group: IntroductionGroup
    ): List<IntroductionResponse> {

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
    // POST /introductions/process
    // ------------------------------------------------------------

    suspend fun processIncomingIntroductions() {

        val creds = requireCreds()

        val endpoint = "/connections/introductions/process"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = "{}",
            secret = creds.secret
        )

        throwForFailure(response)
    }

    // ------------------------------------------------------------
    // POST /introductions/auto-accept
    // ------------------------------------------------------------

    suspend fun autoAcceptEligibleIntroductions() {

        val creds = requireCreds()

        val endpoint = "/connections/introductions/auto-accept"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = "{}",
            secret = creds.secret
        )

        throwForFailure(response)
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
