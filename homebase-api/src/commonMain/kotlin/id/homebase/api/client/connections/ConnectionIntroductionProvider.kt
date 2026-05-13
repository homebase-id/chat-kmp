package id.homebase.api.client.connections

import co.touchlab.kermit.Logger
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
    // POST /introductions/preflight
    // ------------------------------------------------------------

    /**
     * Asks the server to report per-recipient readiness without creating any
     * introduction records. Same auth as [sendIntroductions]; same request body
     * (an [IntroductionGroup]); response is an [IntroductionPreflightResult].
     *
     * Server behaviour:
     *   - 200 with one entry per recipient (sender's own identity filtered out).
     *   - 400 if the recipient list is empty/invalid (client-side require()
     *     below short-circuits the empty case).
     */
    override suspend fun preflightIntroductions(
        group: IntroductionGroup
    ): IntroductionPreflightResult {

        require(group.recipients.isNotEmpty()) {
            "Recipients cannot be empty"
        }

        val creds = requireCreds()

        val endpoint = "/connections/introductions/preflight"

        Logger.i(tag = TAG) {
            "preflightIntroductions: POST recipients=${group.recipients.map { it.domainName }}"
        }
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(group),
            secret = creds.secret
        )

        try {
            throwForFailure(response)
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) {
                "preflightIntroductions: FAIL status=${response.status} recipients=${group.recipients.map { it.domainName }}"
            }
            throw e
        }
        val body: IntroductionPreflightResult = deserialize(response.body)
        // Log the full per-recipient row so the user can see *why* a recipient
        // wasn't Ready (the boolean convenience fields + free-form `detail`
        // carry the explanation; the enum alone is often too coarse for
        // diagnosis — e.g. IntroductionsNotPermitted vs RecipientRejected both
        // surface as "peer refused" but the underlying cause differs).
        body.recipients.forEach { entry ->
            Logger.i(tag = TAG) {
                "preflightIntroductions: ${entry.recipient.domainName} → status=${entry.status} " +
                    "isConfigured=${entry.isConfigured} requiresUpgrade=${entry.requiresUpgrade} " +
                    "allowsIntroductions=${entry.allowsIntroductions} detail=${entry.detail}"
            }
        }
        Logger.i(tag = TAG) {
            "preflightIntroductions: OK   allReady=${body.allReady} recipients=${body.recipients.size}"
        }
        return body
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
