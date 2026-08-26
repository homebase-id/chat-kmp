package id.homebase.api.client.mail

import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Wire models for /api/v2/mail. Mirrors odin-core `Odin.Services.Email.MailAppStatusResult`
 * and friends — the server's naming strategy is camelCase, so field names must match exactly.
 * `ignoreUnknownKeys` is on, so a misspelling here fails SILENTLY as a null/default rather
 * than an error; MailModelsSerializationTest is the guard.
 */
@Serializable
data class MailAppStatus(
    /** Whether this host runs tenant mail at all. False on every host today. */
    val tenantMailEnabled: Boolean = false,
    /** The email drive exists and this app holds Read+Write on it. */
    val driveProvisioned: Boolean = false,
    val mailboxProvisioned: Boolean = false,
    val primaryEmailAddress: String? = null,
    /** A public certificate is published — the server-side "email is on" signal. */
    val activated: Boolean = false,
    /**
     * What to type into a mail app. Null when the host publishes no mail hosts, so the screen
     * shows nothing rather than a form pointing at an empty server name.
     */
    val clientSettings: MailClientSettings? = null,
    val publicKeyFingerprint: String? = null,
    val publishedAt: Long? = null,
    val dkimRecords: List<MailDnsRecord> = emptyList(),
    /** The drive file holding the current secret keyring, once one exists. */
    @Serializable(with = UuidSerializer::class)
    val currentKeyFileUniqueId: Uuid? = null,
)

/**
 * Hostnames, ports and username for setting up a mail app by hand — the same values the
 * server publishes as autoconfig XML, mirroring odin-core `MailClientSettings`.
 *
 * Ports are implicit TLS (993 / 465), NOT STARTTLS on 587: a client given the wrong pairing
 * does not error, it hangs. The username is the FULL address, not the local part, and the
 * outgoing password is the same app password as incoming.
 */
@Serializable
data class MailClientSettings(
    val incomingHost: String = "",
    val incomingPort: Int = 0,
    /** "SSL" — implicit TLS. */
    val incomingSocketType: String = "",
    val outgoingHost: String = "",
    val outgoingPort: Int = 0,
    val outgoingSocketType: String = "",
    val username: String = "",
)

/**
 * A DNS record the identity's zone needs. Shaped by the server's DnsConfig; only the fields the
 * app actually shows are declared.
 */
@Serializable
data class MailDnsRecord(
    val type: String = "",
    /** The record's label, e.g. "s1._domainkey". */
    val name: String = "",
    /** The fully-qualified name the record is published at — what you paste into a DNS provider. */
    val domain: String = "",
    val value: String = "",
    val description: String = "",
    /**
     * The server's lookup verdict: "success" when the record is published correctly, otherwise
     * "unknown" / "domainOrRecordNotFound" / "incorrectValue" / ... Only meaningful on records
     * that came from a health check; the DKIM set returned by activation leaves it empty.
     */
    val status: String = "",
)

/**
 * Whether the identity's email actually WORKS, as opposed to how far setup got —
 * `GET /api/v2/mail/health`, mirroring odin-core `MailAppHealthResult`.
 *
 * [MailAppStatus] answers only the second question, so an identity whose domain has no MX
 * reports as fully configured while nothing can deliver mail to it.
 *
 * The server decides: [brokenRecords] is already filtered and [needsAttention] is already
 * computed, so this app renders the verdict rather than re-deriving it. Re-deriving it here
 * would let the app and the owner console disagree about the same identity.
 */
@Serializable
data class MailAppHealth(
    val tenantMailEnabled: Boolean = false,
    /** False when email was never activated: nothing to report, rather than everything broken. */
    val activated: Boolean = false,
    val records: List<MailDnsRecord> = emptyList(),
    val brokenRecords: List<MailDnsRecord> = emptyList(),
    /** Checks a record comparison cannot make: DKIM pair proof, public-key drift. */
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val needsAttention: Boolean = false,
)

/**
 * Server half of the encrypt/decrypt round-trip check: decrypt [encryptedNonceBase64] with the
 * keyring from the email drive and compare its SHA-256 against [nonceSha256Base64].
 */
@Serializable
data class MailRoundTripChallenge(
    val encryptedNonceBase64: String = "",
    val nonceSha256Base64: String = "",
)

/** Result of creating the mailbox. */
@Serializable
data class MailboxSetupResult(
    val primaryEmailAddress: String = "",
    /** False for manual-DNS identities — the records are shown as instructions instead. */
    val dnsRecordsWritten: Boolean = false,
    val dkimRecords: List<MailDnsRecord> = emptyList(),
)

/**
 * Result of generating a keyring. The private half is NOT here — the server wrote it straight to
 * the email drive, and [keyFileUniqueId] is where to read it back from.
 */
@Serializable
data class EmailKeyGenerationResult(
    @Serializable(with = UuidSerializer::class)
    val keyFileUniqueId: Uuid? = null,
    val fingerprintHex: String = "",
    /** Whether the entropy this app collected was actually mixed in. */
    val clientEntropyUsed: Boolean = false,
)

/**
 * A newly issued mail-client credential. [secret] is in transit exactly once — write it to the
 * drive before showing it to anyone.
 */
@Serializable
data class AppPasswordIssueResult(
    val id: String = "",
    val secret: String = "",
    val label: String = "",
    val createdAt: Long = 0,
)

/**
 * How the mailbox is doing. [available] is false when the mail server does not report, in which
 * case nothing here should be shown rather than shown as zero.
 */
@Serializable
data class MailboxStatusResult(
    val available: Boolean = false,
    val usedBytes: Long = 0,
    val quotaBytes: Long? = null,
    val inboxTotal: Int = 0,
    /** The number worth showing, and what the toolbar badge keys off. */
    val inboxUnread: Int = 0,
    val junkTotal: Int = 0,
    /** Above zero for long means mail is not getting out. */
    val queuedOutbound: Int = 0,
)
