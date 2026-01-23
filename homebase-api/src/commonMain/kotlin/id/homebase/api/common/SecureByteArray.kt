package id.homebase.homebasekmppoc.prototype.lib.core

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

@Serializable
class SecureByteArray(private val bytes: ByteArray) {

    constructor(base64: String) : this(Base64.decode(base64))

    // Direct access—returns the internal array reference (mutable!)
    val unsafeBytes: ByteArray get() = bytes

    fun toByteArray(): ByteArray = bytes.copyOf()

    fun base64Encode(): String {
        return Base64.encode(bytes)
    }

    fun clear() {
        bytes.fill(0)
    }

    // Manual content-based equals (cross-platform)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecureByteArray) return false
        if (bytes.size != other.bytes.size) return false
        for (i in bytes.indices) {
            if (bytes[i] != other.bytes[i]) return false
        }
        return true
    }

    // Manual content-based hashCode (cross-platform, mimics Java's Arrays.hashCode for bytes)
    override fun hashCode(): Int {
        var result = 1
        for (byte in bytes) {
            result = 31 * result + byte.toInt()
        }
        return result
    }

    // Optional: A custom toString() to avoid leaking contents (e.g., for security)
    override fun toString(): String = "SecureByteArray(size=${bytes.size})"
}

fun ByteArray.toSecureByteArray(): SecureByteArray {
    return SecureByteArray(this)
}

