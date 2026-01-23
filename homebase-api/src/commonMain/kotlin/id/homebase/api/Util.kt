package id.homebase.api

import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

// SEB:TODO this file is a mess of all sorts of platform abstractions and common code. Clean it up!

enum class PlatformType {
    ANDROID,
    IOS,
    JVM,
    JS,
    NATIVE
}

interface Platform {
    val name: PlatformType
}

expect fun getPlatform(): Platform

expect fun isAndroid(): Boolean

expect fun showMessage(title: String, message: String)

// URL encoder compatible with UTF-8 encoding
// SEB:TODO KMP Codecs.kt has library functions for this, use those instead
fun encodeUrl(value: String): String {
    val bytes = value.encodeToByteArray()
    val sb = StringBuilder()
    for (byte in bytes) {
        val b = byte.toInt() and 0xFF
        if ((b >= 48 && b <= 57) ||
                        (b >= 65 && b <= 90) ||
                        (b >= 97 && b <= 122) ||
                        b == 45 ||
                        b == 46 ||
                        b == 95 ||
                        b == 126
        ) { // unreserved characters
            sb.append(b.toChar())
        } else {
            sb.append('%')
            sb.append((b shr 4).toString(16).uppercase())
            sb.append((b and 15).toString(16).uppercase())
        }
    }
    return sb.toString()
}

// URL decoder compatible with UTF-8 encoding
// SEB:TODO KMP Codecs.kt has library functions for this, use those instead
fun decodeUrl(value: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%') {
            if (i + 2 >= value.length) {
                throw IllegalArgumentException(
                        "Invalid URL encoding: incomplete percent sequence at position $i"
                )
            }
            val hex = value.substring(i + 1, i + 3)
            try {
                val b = hex.toInt(16).toByte()
                bytes.add(b)
                i += 3
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException(
                        "Invalid URL encoding: invalid hex sequence '$hex' at position $i",
                        e
                )
            }
        } else if (c == '+') {
            bytes.add(' '.code.toByte())
            i++
        } else {
            bytes.add(c.code.toByte())
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}

// Generate a random UUID as byte array
fun generateUuidBytes(): ByteArray = Uuid.random().toByteArray()

// Generate a random UUID as byte array
fun generateUuidString(): String = Uuid.random().toString()

fun ByteArray.toBase64(): String {
    return Base64.encode(this)
}
