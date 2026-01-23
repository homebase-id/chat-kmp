package id.homebase.api.crypto

import id.homebase.homebasekmppoc.prototype.lib.crypto.toStringFromUtf8Bytes
import id.homebase.homebasekmppoc.prototype.lib.crypto.toUtf8ByteArray
import kotlin.io.encoding.Base64

/**
 * Standard Base64 encoding utilities
 * Provides standard base64 encoding/decoding (RFC 4648 Section 4)
 * Use this for server responses and standard base64 data
 */
object Base64Encoder {

    private val base64 = Base64.Default

    /**
     * Encode byte array to standard base64 string
     */
    fun encode(input: ByteArray): String {
        return base64.encode(input)
    }

    /**
     * Encode string to standard base64 string
     */
    fun encode(input: String): String {
        return encode(input.toUtf8ByteArray())
    }

    /**
     * Decode standard base64 string to byte array
     */
    fun decode(input: String): ByteArray {
        return base64.decode(input)
    }

    /**
     * Decode standard base64 string to UTF-8 string
     */
    fun decodeString(input: String): String {
        return decode(input).toStringFromUtf8Bytes()
    }
}
