package id.homebase.api.client.mail

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient

/**
 * The Email setup app's server surface, /api/v2/mail.
 *
 * Reachable on a plain app token: the server authorizes these calls by the app's Read+Write
 * access to the email drive rather than by a permission key, so nothing here needs the owner
 * console. Bodies ride the usual shared-secret envelope.
 */
class MailProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "MailProvider"
        private const val BASE = "/mail"
    }

    /**
     * Everything the app's entry flow branches on. Never gated: it answers before the email drive
     * exists, which is what lets the app tell "this server has no email" apart from "you have not
     * set it up yet".
     */
    suspend fun getStatus(): MailAppStatus {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "$BASE/status"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        throwForFailure(response)
        val status = deserialize<MailAppStatus>(response.body)
        Logger.d(tag = TAG) {
            "status: enabled=${status.tenantMailEnabled} drive=${status.driveProvisioned} " +
                "mailbox=${status.mailboxProvisioned} activated=${status.activated}"
        }
        return status
    }

    /**
     * Asks for a message encrypted to the published key, to prove this device's keyring can still
     * read incoming mail. Requires Read+Write on the email drive (403 otherwise) and a published
     * key (400 otherwise).
     */
    suspend fun createRoundTripChallenge(): MailRoundTripChallenge {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "$BASE/challenge"),
            token = creds.accessToken,
            jsonBody = "{}",
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize<MailRoundTripChallenge>(response.body)
    }
}
