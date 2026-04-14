package id.homebase.api.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class Md5Test {

    @Test
    fun emptyString() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hash("".encodeToByteArray()).toHex())
    }

    @Test
    fun rfc1321Vectors() {
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Md5.hash("a".encodeToByteArray()).toHex())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hash("abc".encodeToByteArray()).toHex())
        assertEquals(
            "f96b697d7cb7938d525a2f31aaf161d0",
            Md5.hash("message digest".encodeToByteArray()).toHex()
        )
        assertEquals(
            "c3fcd3d76192e4007dfb496cca67e13b",
            Md5.hash("abcdefghijklmnopqrstuvwxyz".encodeToByteArray()).toHex()
        )
        assertEquals(
            "d174ab98d277d9f5a5611c2c9f419d9f",
            Md5.hash(
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".encodeToByteArray()
            ).toHex()
        )
    }

    @Test
    fun boundaryLengths() {
        // 55-byte input (just below single-block pad threshold) and 56-byte (forces second block)
        assertEquals(
            "c9ccf168914a1bcfc3229f1948e67da0",
            Md5.hash("1234567890123456789012345678901234567890123456789012345".encodeToByteArray()).toHex()
        )
        assertEquals(
            "57edf4a22be3c955ac49da2e2107b67a",
            Md5.hash(
                "12345678901234567890123456789012345678901234567890123456789012345678901234567890".encodeToByteArray()
            ).toHex()
        )
    }

    @Test
    fun toGuidIdProducesParsableUuid() {
        val id = Md5.toGuidId("frodo.baggins.shire")
        assertEquals(id, Uuid.parse(id.toString()))
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF).toString(16).padStart(2, '0')) }
}
