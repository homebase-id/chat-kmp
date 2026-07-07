package id.homebase.api.common

import kotlin.uuid.Uuid
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import id.homebase.api.crypto.ByteArrayUtil.reduceSha256Hash
import id.homebase.api.crypto.ByteArrayUtil.reduceSha256HashSync
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive


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

object OdinIdSerializerNullable : KSerializer<OdinId?> {
    override val descriptor = PrimitiveSerialDescriptor("OdinId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OdinId?) {
        if (value != null) {
            encoder.encodeString(value.domainName) // Assuming domainName is the string representation
        } else {
            encoder.encodeNull()
        }
    }

    override fun deserialize(decoder: Decoder): OdinId? {
        val jsonDecoder = decoder as? JsonDecoder ?: return OdinId(decoder.decodeString())

        val element = jsonDecoder.decodeJsonElement()

        if (element is JsonNull) return null

        val value = element.jsonPrimitive.contentOrNull ?: return null
        if (value.isBlank()) return null

        return OdinId(value)
    }
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
        _domainName = AsciiDomainName(identifier),
        _hash = cachedHash(identifier)
    )

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

        // Thread-safe cache: a chat app sees a small set of unique domains
        // (conversation participants), so this stays tiny. The SHA-256 hash
        // is deterministic and domain names don't change, so entries never
        // need invalidation.
        private val hashLock = SynchronizedObject()
        private val hashCache = HashMap<String, Uuid>()

        internal fun cachedHash(identifier: String): Uuid {
            val key = AsciiDomainName(identifier).domainName
            synchronized(hashLock) { hashCache[key] }?.let { return it }
            val hash = reduceSha256HashSync(key)
            return synchronized(hashLock) { hashCache.getOrPut(key) { hash } }
        }

        /** Deterministic hash of any `AsciiDomainName`. */
        suspend fun toHashId(domainName: AsciiDomainName): Uuid = reduceSha256Hash(domainName.domainName)

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

/* ----------------------------- Public profile-image URL ----------------------------- */

/** Path suffix of the public profile-image endpoint; shared with PublicImageFetcher's URL matching. */
const val PUB_IMAGE_PATH = "/pub/image"

/** Canonical public-image URL for a raw domain string (use the OdinId overload when one is in hand). */
fun publicImageUrl(domain: String): String = "https://$domain$PUB_IMAGE_PATH"

/** Canonical public profile-image URL — the single source of truth for the /pub/image URL shape. */
fun OdinId.publicImageUrl(): String = publicImageUrl(domainName)