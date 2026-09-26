package id.homebase.core.ui.screens.card

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CardNfcTagTest {
    private val url = "https://frodo.dotyou.cloud/card"

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val selectApp = hex("00 A4 04 00 07 D2760000850101 00")
    private val selectCc = hex("00 A4 00 0C 02 E103")
    private val selectNdef = hex("00 A4 00 0C 02 E104")
    private fun read(offset: Int, le: Int) = byteArrayOf(0x00, 0xB0.toByte(), (offset ushr 8).toByte(), offset.toByte(), le.toByte())

    private val ok = hex("9000")
    private val notFound = hex("6A82")

    private fun body(response: ByteArray): ByteArray {
        assertContentEquals(ok, response.copyOfRange(response.size - 2, response.size))
        return response.copyOfRange(0, response.size - 2)
    }

    // Reads the tag the way Android's and iOS's NDEF readers do and returns the NDEF message.
    private fun readNdef(tag: Type4TagEmulator, chunk: Int = 0x3B): ByteArray {
        assertContentEquals(ok, tag.process(selectApp))
        assertContentEquals(ok, tag.process(selectCc))
        val cc = body(tag.process(read(0, 15)))
        assertEquals(15, cc.size)
        assertContentEquals(hex("E104"), cc.copyOfRange(9, 11))
        assertContentEquals(ok, tag.process(selectNdef))
        val nlenBytes = body(tag.process(read(0, 2)))
        val nlen = ((nlenBytes[0].toInt() and 0xFF) shl 8) or (nlenBytes[1].toInt() and 0xFF)
        var message = ByteArray(0)
        while (message.size < nlen) {
            message += body(tag.process(read(2 + message.size, minOf(chunk, nlen - message.size))))
        }
        return message
    }

    private fun parseUri(message: ByteArray): String {
        val header = message[0].toInt() and 0xFF
        assertEquals(0xC0, header and 0xC0, "MB and ME")
        assertEquals(0x01, header and 0x07, "TNF well-known")
        val typeLength = message[1].toInt()
        val shortRecord = header and 0x10 != 0
        val (payloadLength, at) = if (shortRecord) {
            (message[2].toInt() and 0xFF) to 3
        } else {
            ((message[2].toInt() and 0xFF) shl 24 or ((message[3].toInt() and 0xFF) shl 16) or
                ((message[4].toInt() and 0xFF) shl 8) or (message[5].toInt() and 0xFF)) to 6
        }
        assertEquals("U", message.copyOfRange(at, at + typeLength).decodeToString())
        val payload = message.copyOfRange(at + typeLength, message.size)
        assertEquals(payloadLength, payload.size)
        val prefix = when (payload[0].toInt()) {
            0x00 -> ""
            0x01 -> "http://www."
            0x02 -> "https://www."
            0x03 -> "http://"
            0x04 -> "https://"
            else -> error("unexpected prefix ${payload[0]}")
        }
        return prefix + payload.copyOfRange(1, payload.size).decodeToString()
    }

    @Test
    fun ndefMessageBytesAreExact() {
        val expected = hex("D1 01 18 55 04") + "frodo.dotyou.cloud/card".encodeToByteArray()
        assertContentEquals(expected, ndefUriMessage(url))
    }

    @Test
    fun selectAndReadReturnsCapabilityContainerAndMessage() {
        val tag = Type4TagEmulator({ url })
        assertContentEquals(ok, tag.process(selectApp))
        assertContentEquals(ok, tag.process(selectCc))
        val message = ndefUriMessage(url)
        val fileSize = message.size + 2
        assertContentEquals(
            hex("000F 20 00F6 00F6 04 06 E104") + byteArrayOf((fileSize ushr 8).toByte(), fileSize.toByte()) + hex("00 FF") + ok,
            tag.process(read(0, 15)),
        )
        assertContentEquals(ok, tag.process(selectNdef))
        assertContentEquals(
            byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()) + message + ok,
            tag.process(read(0, 0)),
        )
    }

    @Test
    fun readBinarySlicesByOffsetAndLength() {
        val tag = Type4TagEmulator({ url })
        tag.process(selectApp)
        tag.process(selectNdef)
        val file = body(tag.process(read(0, 0)))
        assertContentEquals(file.copyOfRange(5, 9) + ok, tag.process(read(5, 4)))
        assertContentEquals(file.copyOfRange(file.size - 3, file.size) + ok, tag.process(read(file.size - 3, 50)))
        assertContentEquals(ok, tag.process(read(file.size, 10)))
        assertContentEquals(hex("6B00"), tag.process(read(file.size + 1, 10)))
    }

    @Test
    fun unknownAidAndFileAreNotFound() {
        val tag = Type4TagEmulator({ url })
        assertContentEquals(notFound, tag.process(hex("00 A4 04 00 07 A0000000031010 00")))
        assertContentEquals(ok, tag.process(selectApp))
        assertContentEquals(notFound, tag.process(hex("00 A4 00 0C 02 E105")))
        assertContentEquals(hex("6986"), tag.process(read(0, 2)))
    }

    @Test
    fun fileSelectBeforeApplicationSelectIsNotFound() {
        val tag = Type4TagEmulator({ url })
        assertContentEquals(notFound, tag.process(selectNdef))
    }

    @Test
    fun inactiveTagAnswersNotFound() {
        var current: String? = null
        val tag = Type4TagEmulator({ current })
        assertContentEquals(notFound, tag.process(selectApp))
        current = url
        assertContentEquals(ok, tag.process(selectApp))
        assertContentEquals(ok, tag.process(selectNdef))
        current = null
        assertContentEquals(notFound, tag.process(read(0, 2)))
    }

    @Test
    fun aChangedLinkIsServedFromTheNextSession() {
        var served = url
        val tag = Type4TagEmulator({ served })
        assertEquals(url, parseUri(readNdef(tag)))
        tag.reset()
        served = "https://frodo.dotyou.cloud/card?design=board"
        assertEquals(served, parseUri(readNdef(tag)))
    }

    @Test
    fun aFileSelectAfterDeactivationNeedsTheApplicationAgain() {
        val tag = Type4TagEmulator({ url })
        readNdef(tag)
        tag.reset()
        assertContentEquals(notFound, tag.process(selectNdef))
    }

    @Test
    fun deactivationForgetsTheSelection() {
        val tag = Type4TagEmulator({ url })
        tag.process(selectApp)
        tag.process(selectNdef)
        tag.reset()
        assertContentEquals(hex("6986"), tag.process(read(0, 2)))
    }

    @Test
    fun malformedCommandsAreRejected() {
        val tag = Type4TagEmulator({ url })
        assertContentEquals(hex("6700"), tag.process(hex("00A4")))
        assertContentEquals(hex("6700"), tag.process(hex("00 A4 04 00 07 D27600")))
        assertContentEquals(hex("6E00"), tag.process(hex("90 A4 04 00 00")))
        assertContentEquals(hex("6D00"), tag.process(hex("00 D6 00 00 01 00")))
    }

    @Test
    fun roundTripsShortUrl() {
        assertEquals(url, parseUri(readNdef(Type4TagEmulator({ url }))))
    }

    @Test
    fun longUrlUsesLongRecordAndRoundTrips() {
        val label = "a".repeat(63)
        val longUrl = "https://$label.$label.$label.${"b".repeat(60)}.cloud/card"
        val message = ndefUriMessage(longUrl)
        assertEquals(0, message[0].toInt() and 0x10, "SR must be clear over 255 payload bytes")
        assertEquals(longUrl, parseUri(message))
        assertEquals(longUrl, parseUri(readNdef(Type4TagEmulator({ longUrl }))))
    }

    @Test
    fun payloadOfExactly255BytesStaysShort() {
        val host = "c".repeat(254 - "/card".length)
        val edgeUrl = "https://$host/card"
        val message = ndefUriMessage(edgeUrl)
        assertEquals(0x10, message[0].toInt() and 0x10)
        assertEquals(0xFF, message[2].toInt() and 0xFF)
        assertEquals(edgeUrl, parseUri(message))
    }

    @Test
    fun reportsOnceWhenTheLastChunkOfTheMessageIsRead() {
        var reads = 0
        val tag = Type4TagEmulator({ url }, onMessageRead = { reads++ })
        tag.process(selectApp)
        tag.process(selectCc)
        tag.process(read(0, 15))
        assertEquals(0, reads, "reading the whole CC file is not a share")
        tag.process(selectNdef)
        val fileSize = ndefUriMessage(url).size + 2
        tag.process(read(0, 2))
        tag.process(read(2, 10))
        assertEquals(0, reads)
        tag.process(read(12, fileSize - 12))
        assertEquals(1, reads)
        tag.process(read(0, 0))
        assertEquals(2, reads, "every full read reports; TapShareDebounce collapses a lingering tap")
    }

    @Test
    fun wholeFileInOneReadReports() {
        var reads = 0
        val tag = Type4TagEmulator({ url }, onMessageRead = { reads++ })
        tag.process(selectApp)
        tag.process(selectNdef)
        tag.process(read(0, 0))
        assertEquals(1, reads)
    }

    @Test
    fun newSessionReportsAgain() {
        var reads = 0
        val tag = Type4TagEmulator({ url }, onMessageRead = { reads++ })
        readNdef(tag)
        tag.reset()
        readNdef(tag)
        assertEquals(2, reads)
    }

    @Test
    fun zeroLengthReadAtTheEndIsNotAShare() {
        var reads = 0
        val tag = Type4TagEmulator({ url }, onMessageRead = { reads++ })
        tag.process(selectApp)
        tag.process(selectNdef)
        tag.process(read(ndefUriMessage(url).size + 2, 10))
        assertEquals(0, reads)
    }

    @Test
    fun inactiveTagNeverReports() {
        var reads = 0
        val tag = Type4TagEmulator({ null }, onMessageRead = { reads++ })
        tag.process(selectApp)
        tag.process(selectNdef)
        tag.process(read(0, 0))
        assertEquals(0, reads)
    }
}
