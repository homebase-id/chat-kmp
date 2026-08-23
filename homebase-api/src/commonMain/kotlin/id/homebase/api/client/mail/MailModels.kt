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
    val publicKeyFingerprint: String? = null,
    val publishedAt: Long? = null,
    val dkimRecords: List<MailDnsRecord> = emptyList(),
    /** The drive file holding the current secret keyring, once one exists. */
    @Serializable(with = UuidSerializer::class)
    val currentKeyFileUniqueId: Uuid? = null,
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
