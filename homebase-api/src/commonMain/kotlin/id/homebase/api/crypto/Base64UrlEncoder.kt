package id.homebase.api.crypto

import id.homebase.homebasekmppoc.prototype.lib.crypto.toStringFromUtf8Bytes
import id.homebase.homebasekmppoc.prototype.lib.crypto.toUtf8ByteArray
import kotlin.io.encoding.Base64

/**
 * Base64 URL encoding utilities
 * Provides URL-safe base64 encoding/decoding (RFC 4648 Section 5)
 */
object Base64UrlEncoder {

    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /**
     * Encode byte array to base64url string
     */
    fun encode(input: ByteArray): String {
        return base64Url.encode(input)
    }

    /**
     * Encode string to base64url string
     */
    fun encode(input: String): String {
        return encode(input.toUtf8ByteArray())
    }

    /**
     * Decode base64url string to byte array
     */
    fun decode(input: String): ByteArray {
        return base64Url.decode(input)
    }

    /**
     * Decode base64url string to UTF-8 string
     */
    fun decodeString(input: String): String {
        return decode(input).toStringFromUtf8Bytes()
    }
}
