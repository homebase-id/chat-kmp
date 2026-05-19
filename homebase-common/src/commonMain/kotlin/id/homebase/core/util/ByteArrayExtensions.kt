package id.homebase.core.util

fun ByteArray.trimToUtf8Boundary(): ByteArray {
    if (isEmpty()) return this
    val last = this[size - 1].toInt() and 0xFF
    if (last and 0x80 == 0) return this // ends with ASCII

    var startBytePos = size - 1
    while (startBytePos > 0 && (this[startBytePos].toInt() and 0xC0) == 0x80) {
        startBytePos--
    }
    val b = this[startBytePos].toInt() and 0xFF
    val expectedLen = when {
        b and 0xE0 == 0xC0 -> 2
        b and 0xF0 == 0xE0 -> 3
        b and 0xF8 == 0xF0 -> 4
        else -> 1
    }
    val actual = size - startBytePos
    return if (actual >= expectedLen) this else copyOf(startBytePos)
}
