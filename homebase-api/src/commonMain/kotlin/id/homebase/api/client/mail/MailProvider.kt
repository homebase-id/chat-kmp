package id.homebase.api.client.mail

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.serialization.OdinSystemSerializer
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
     * Creates the mailbox: DKIM keys, DNS records, the account. Idempotent, so a client that was
     * killed mid-setup calls it again instead of tracking where it got to.
     */
    suspend fun ensureMailbox(primaryEmailAddress: String): MailboxSetupResult {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "$BASE/setup/mailbox"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                EnsureMailboxRequest(primaryEmailAddress = primaryEmailAddress)
            ),
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize<MailboxSetupResult>(response.body)
    }

    /**
     * Generates the identity's keyring — the LAST setup step. The server writes it to the email
     * drive before publishing its certificate, so nothing is lost if this app dies right after.
     *
     * [clientEntropyBase64] is optional additional entropy (the shake screen); the server mixes it
     * into its own generator rather than substituting it, and generation is never blocked on it.
     */
    suspend fun generateKey(
        primaryEmailAddress: String,
        clientEntropyBase64: String = "",
    ): EmailKeyGenerationResult {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "$BASE/setup/keys"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                GenerateEmailKeyRequest(
                    primaryEmailAddress = primaryEmailAddress,
                    clientEntropyBase64 = clientEntropyBase64,
                )
            ),
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize<EmailKeyGenerationResult>(response.body)
    }

    /**
     * Issues a mail-client credential. Requires a published key, so it comes AFTER key generation.
     * The secret is returned once — persist it before showing it.
     */
    suspend fun issueAppPassword(primaryEmailAddress: String, label: String): AppPasswordIssueResult {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "$BASE/app-passwords"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                IssueAppPasswordRequest(primaryEmailAddress = primaryEmailAddress, label = label)
            ),
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize<AppPasswordIssueResult>(response.body)
    }

    /**
     * Revokes a credential on the mail server. Deleting our own drive record of it revokes
     * nothing. Idempotent, so reconciling against an id the server no longer knows is fine.
     */
    suspend fun revokeAppPassword(id: String) {
        val creds = requireCreds()
        val response = encryptedDelete(
            url = apiUrl(creds.domain, "$BASE/app-passwords/$id"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        throwForFailure(response)
    }

    /** Mailbox storage, or available = false when the mail server does not report it. */
    suspend fun getStorage(): MailStorageResult {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "$BASE/storage"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize<MailStorageResult>(response.body)
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

@kotlinx.serialization.Serializable
private data class EnsureMailboxRequest(val primaryEmailAddress: String)

@kotlinx.serialization.Serializable
private data class GenerateEmailKeyRequest(
    val primaryEmailAddress: String,
    val clientEntropyBase64: String,
)

@kotlinx.serialization.Serializable
private data class IssueAppPasswordRequest(val primaryEmailAddress: String, val label: String)
