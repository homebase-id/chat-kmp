package id.homebase.api.common

import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import id.homebase.api.crypto.ByteArrayUtil.reduceSha256Hash
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.descriptors.SerialDescriptor


/**
 * Serializes `OdinId` **as its domain name string** (the same value you see in `toString()` / `domainName`).
 * Deserialization goes through the public `OdinId(String)` constructor → validation + hash recomputation.
 */
object OdinIdSerializer : KSerializer<OdinId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OdinId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OdinId) {
        encoder.encodeString(value.domainName)
    }

    override fun deserialize(decoder: Decoder): OdinId =
        OdinId(decoder.decodeString())   // calls your public constructor → validates + hashes
}
/**
 * Holds the identity for an individual using the dotYou platform.
 *
 * This is the Kotlin Multiplatform equivalent of the original C# `OdinId` readonly struct.
 * It is immutable, value-like, and behaves identically for equality, hashing, conversions, etc.
 */
@Serializable(with = OdinIdSerializer::class)
class OdinId public constructor(
    private val _domainName: AsciiDomainName,
    private val _hash: Uuid
) {

    /** The domain name (guaranteed trimmed, RFC-compliant, lower-cased). */
    val domainName: String get() = _domainName.domainName

    /** The underlying `AsciiDomainName` wrapper. */
    val asciiDomain: AsciiDomainName get() = _domainName

    /**
     * Creates an `OdinId` from a raw domain name string.
     * The string is validated and normalized by `AsciiDomainName`.
     */
    constructor(identifier: String) : this(
        _domainName = AsciiDomainName(identifier), // throws if invalid/null
        _hash = runBlocking { calculateHash(AsciiDomainName(identifier)) }
    )

    /**
     * Creates an `OdinId` from an already-validated `AsciiDomainName`.
     */
//    constructor(asciiDomain: AsciiDomainName) : this(
//        _domainName = asciiDomain,
//        _hash = runBlocking { calculateHash(asciiDomain) }
//    )

    override fun equals(other: Any?): Boolean {
        if (other !is OdinId) return false
        return this.toHashId() == other.toHashId()
    }

    override fun hashCode(): Int = _domainName.domainName.hashCode()

    override fun toString(): String = _domainName.domainName

    /** Returns the deterministic 128-bit hash (as `Uuid`) of the domain name (lowercased before hashing). */
    fun toHashId(): Uuid = _hash

    /** UTF-8 bytes of the domain name. */
    fun toByteArray(): ByteArray = _domainName.domainName.encodeToByteArray()

    companion object {

        /** Deterministic hash of any `AsciiDomainName`. */
        suspend fun toHashId(domainName: AsciiDomainName): Uuid = calculateHash(domainName)

        /** Creates an `OdinId` from UTF-8 bytes (the bytes are interpreted as the domain string). */
        fun fromByteArray(id: ByteArray): OdinId = OdinId(id.decodeToString())

        /** Quick validity check (delegates to `AsciiDomainNameValidator`). */
        fun isValid(odinId: String?): Boolean = AsciiDomainNameValidator.tryValidateDomain(odinId)

        /** Throws if the string is not a valid OdinId domain. */
        fun validate(odinId: String?) {
            if (odinId.isNullOrBlank())
                throw IllegalArgumentException("Domain cannot be null or blank")
            AsciiDomainNameValidator.assertValidDomain(odinId)
        }

        private suspend fun calculateHash(asciiDomain: AsciiDomainName): Uuid {
            // Matches the original C# behavior:
            // SHA-256 → reduced to 16 bytes → Guid/Uuid
            val reduced = reduceSha256Hash(asciiDomain.domainName)
            return reduced // must be exactly 16 bytes
        }
    }
}

/* ----------------------------- Convenience conversions (mimic C# implicit/explicit operators) ----------------------------- */

/** `odinId` → domain string (same as `toString()`) */
fun OdinId.asString(): String = domainName

/** String → `OdinId` (explicit cast equivalent) */
fun String.toOdinId(): OdinId = OdinId(this)

/** `OdinId` → `AsciiDomainName` (implicit) */
fun OdinId.asAsciiDomainName(): AsciiDomainName = asciiDomain

/** `AsciiDomainName` → `OdinId` (explicit) */
fun AsciiDomainName.toOdinId(): OdinId = OdinId(this.domainName)

/** `OdinId` → deterministic `Uuid` (implicit) */
fun OdinId.asUuid(): Uuid = toHashId()