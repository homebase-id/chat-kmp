package id.homebase.core.ui.screens.email.model

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * The file shapes on the Email app's drive.
 *
 * Mirrored from odin-core `src/services/Odin.Services/Email/EmailDriveFileTypes.cs` — change one,
 * change both.
 *
 * Each type has exactly one writer. The server owns the key material and the pointer to the
 * current key; this app owns its record of the credentials it asked for. Two writers on one file
 * type would mean a read-modify-write race for no gain.
 */
object EmailFileTypes {
    /** One OpenPGP keyring, written by the SERVER. Append-only; older keys open older mail. */
    const val KEY_MATERIAL = 7301

    /** Which keyring is current, written by the SERVER. Singleton. */
    const val CURRENT_KEY_POINTER = 7302

    /** One issued mail-client credential, written by THIS APP. */
    const val APP_PASSWORD_CREDENTIAL = 7304

    val CURRENT_KEY_POINTER_UNIQUE_ID: Uuid = Uuid.parse("7e0c1d54-6b3a-4f28-9a71-c50f2d84b3e6")
}

/**
 * A keyring, both halves. The private half exists nowhere else — not on the server, which
 * generated it and kept only the certificate.
 */
@Serializable
data class EmailKeyMaterialContent(
    val secretKeyArmored: String = "",
    val publicCertificateArmored: String = "",
    val fingerprintHex: String = "",
    /** The address the key is bound to (its OpenPGP user id). */
    val userId: String = "",
    val createdUtc: Long = 0,
)

/** Points at the keyring currently being published and encrypted to. */
@Serializable
data class EmailCurrentKeyContent(
    @Serializable(with = UuidSerializer::class)
    val keyFileUniqueId: Uuid? = null,
    val fingerprintHex: String = "",
    val updatedUtc: Long = 0,
)

/**
 * One mail-client credential, as this app recorded it.
 *
 * The secret is kept because the mail server generates it and never shows it again; [id] is kept
 * because it is the only handle a revoke has. Deleting this file revokes nothing — revoking is a
 * call to the server.
 */
@Serializable
data class EmailCredentialContent(
    /** The mail server's id for this credential. Required to revoke it. */
    val id: String = "",
    val label: String = "",
    val secret: String = "",
    val emailAddress: String = "",
    val createdUtc: Long = 0,
)

fun HomebaseFile.toEmailKeyMaterial(): EmailKeyMaterialContent? = decodeEmailContent()

fun HomebaseFile.toEmailCurrentKey(): EmailCurrentKeyContent? = decodeEmailContent()

fun HomebaseFile.toEmailCredential(): EmailCredentialContent? = decodeEmailContent()

/**
 * The sync pipeline decrypts appData.content before storing it, so this reads plaintext JSON.
 * A file that fails to decode is skipped rather than thrown on: one unreadable file must not
 * take out the whole screen.
 */
private inline fun <reified T> HomebaseFile.decodeEmailContent(): T? {
    val content = fileMetadata.appData.content ?: return null
    if (content.isEmpty()) return null
    return runCatching { OdinSystemSerializer.deserialize<T>(content) }.getOrNull()
}

/**
 * A keyring as the UI needs it. Carries the armored halves because exporting the private key into
 * a mail client is the whole reason external clients can read encrypted mail.
 */
@androidx.compose.runtime.Immutable
data class EmailKeyRef(
    val uniqueId: Uuid,
    val fingerprintHex: String,
    val userId: String,
    val createdUtc: Long,
    val secretKeyArmored: String,
    val publicCertificateArmored: String,
) {
    /** Grouped in fours, the way OpenPGP fingerprints are normally shown. */
    val displayFingerprint: String
        get() = fingerprintHex.chunked(4).joinToString(" ")
}

/** An issued mail-client credential, as this app recorded it. */
@androidx.compose.runtime.Immutable
data class EmailCredential(
    /** The drive file, for forgetting our record of it. */
    val fileId: Uuid,
    /** The mail server's id, for revoking it. */
    val id: String,
    val label: String,
    val secret: String,
    val emailAddress: String,
    val createdUtc: Long,
)
