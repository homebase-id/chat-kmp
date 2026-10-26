package id.homebase.chat.services

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256ContractTest {

    private fun hex(input: ByteArray) = sha256(input).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun emptyInputIsTheEmptyMessageDigest() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(ByteArray(0)))
    }

    @Test
    fun abcKnownAnswer() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc".encodeToByteArray()))
    }

    @Test
    fun multiBlockKnownAnswer() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun textIsHashedAsUtf8Bytes() {
        assertEquals(
            hex(byteArrayOf(0xC3.toByte(), 0xA6.toByte(), 0xC3.toByte(), 0xB8.toByte(), 0xC3.toByte(), 0xA5.toByte())),
            hex("æøå".encodeToByteArray()),
        )
        assertEquals(32, sha256("æøå".encodeToByteArray()).size)
    }
}
