package id.homebase.core.ui.screens.card

private val URI_PREFIXES = listOf(
    "https://www." to 0x02,
    "https://" to 0x04,
    "http://www." to 0x01,
    "http://" to 0x03,
)

private const val TNF_WELL_KNOWN = 0x01
private const val FLAG_MB = 0x80
private const val FLAG_ME = 0x40
private const val FLAG_SR = 0x10

internal fun ndefUriMessage(url: String): ByteArray {
    val (prefix, code) = URI_PREFIXES.firstOrNull { url.startsWith(it.first) } ?: ("" to 0x00)
    val payload = byteArrayOf(code.toByte()) + url.substring(prefix.length).encodeToByteArray()
    val shortRecord = payload.size <= 0xFF
    val header = FLAG_MB or FLAG_ME or TNF_WELL_KNOWN or (if (shortRecord) FLAG_SR else 0)
    val payloadLength = if (shortRecord) {
        byteArrayOf(payload.size.toByte())
    } else {
        byteArrayOf((payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte())
    }
    return byteArrayOf(header.toByte(), 0x01) + payloadLength + byteArrayOf('U'.code.toByte()) + payload
}

internal object Apdu {
    val OK = byteArrayOf(0x90.toByte(), 0x00)
    val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    val NO_CURRENT_EF = byteArrayOf(0x69, 0x86.toByte())
    val WRONG_OFFSET = byteArrayOf(0x6B, 0x00)
    val WRONG_LENGTH = byteArrayOf(0x67, 0x00)
    val INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    val CLA_NOT_SUPPORTED = byteArrayOf(0x6E, 0x00)
}

// NFC Forum Type 4 Tag, read-only, serving one NDEF URI record while [currentUrl] is non-null.
internal class Type4TagEmulator(
    private val currentUrl: () -> String?,
    private val onMessageRead: () -> Unit = {},
) {
    private var filesUrl: String? = null
    private var ccFile: ByteArray? = null
    private var ndefFile: ByteArray? = null
    private var applicationSelected = false
    private var selected: ByteArray? = null

    fun reset() {
        applicationSelected = false
        selected = null
    }

    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return Apdu.WRONG_LENGTH
        if (apdu[0].toInt() != 0x00) return Apdu.CLA_NOT_SUPPORTED
        return when (apdu[1].toInt() and 0xFF) {
            0xA4 -> select(apdu)
            0xB0 -> readBinary(apdu)
            else -> Apdu.INS_NOT_SUPPORTED
        }
    }

    private fun select(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return Apdu.WRONG_LENGTH
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return Apdu.WRONG_LENGTH
        val data = apdu.copyOfRange(5, 5 + lc)
        return when (apdu[2].toInt() and 0xFF) {
            0x04 -> selectApplication(data)
            0x00 -> selectFile(data)
            else -> Apdu.NOT_FOUND
        }
    }

    private fun selectApplication(aid: ByteArray): ByteArray {
        reset()
        val url = currentUrl()
        if (!aid.contentEquals(NDEF_AID) || url == null) return Apdu.NOT_FOUND
        if (url != filesUrl) {
            val message = ndefUriMessage(url)
            val ndef = byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()) + message
            ndefFile = ndef
            ccFile = capabilityContainer(ndef.size)
            filesUrl = url
        }
        applicationSelected = true
        return Apdu.OK
    }

    private fun selectFile(fileId: ByteArray): ByteArray {
        // Stopping mid-exchange must stop the link from being served.
        if (currentUrl() == null) {
            reset()
            return Apdu.NOT_FOUND
        }
        selected = when {
            !applicationSelected -> null
            fileId.contentEquals(CC_FILE_ID) -> ccFile
            fileId.contentEquals(NDEF_FILE_ID) -> ndefFile
            else -> null
        }
        return if (selected == null) Apdu.NOT_FOUND else Apdu.OK
    }

    private fun readBinary(apdu: ByteArray): ByteArray {
        if (currentUrl() == null) {
            reset()
            return Apdu.NOT_FOUND
        }
        val file = selected ?: return Apdu.NO_CURRENT_EF
        if (apdu.size < 5) return Apdu.WRONG_LENGTH
        val offset = ((apdu[2].toInt() and 0x7F) shl 8) or (apdu[3].toInt() and 0xFF)
        if (offset > file.size) return Apdu.WRONG_OFFSET
        val le = (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
        val end = minOf(offset + le, file.size)
        if (file === ndefFile && end == file.size && end > offset) onMessageRead()
        return file.copyOfRange(offset, end) + Apdu.OK
    }

    private fun capabilityContainer(ndefFileSize: Int): ByteArray = byteArrayOf(
        0x00, 0x0F, // CCLEN
        0x20, // mapping version 2.0
        0x00, MAX_LE.toByte(),
        0x00, MAX_LC.toByte(),
        0x04, 0x06, // NDEF File Control TLV
        NDEF_FILE_ID[0], NDEF_FILE_ID[1],
        (ndefFileSize ushr 8).toByte(), ndefFileSize.toByte(),
        0x00, // read access granted
        0xFF.toByte(), // no write access
    )

    companion object {
        private const val MAX_LE = 0xF6
        private const val MAX_LC = 0xF6
        val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03)
        val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04)
    }
}

// Readers re-select and re-read while a phone lingers in the field; one tap reports once, however long it lingers.
internal class TapShareDebounce(private val windowMs: Long = 3_000) {
    private var lastMs: Long? = null

    fun accept(nowMs: Long): Boolean {
        val last = lastMs
        lastMs = nowMs
        return last == null || nowMs - last >= windowMs
    }
}
