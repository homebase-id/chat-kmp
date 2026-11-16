package id.homebase.api.util

/** A growable byte buffer that keeps its capacity between [take]s. */
internal class ByteBuilder(initialCapacity: Int = 1024 * 1024) {
    private var bytes = ByteArray(initialCapacity)
    var size = 0
        private set

    private fun room(n: Int) {
        if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
    }

    fun add(data: ByteArray, from: Int = 0, length: Int = data.size - from) {
        room(length)
        data.copyInto(bytes, size, from, from + length)
        size += length
    }

    fun addByte(value: Int) {
        room(1)
        bytes[size++] = value.toByte()
    }

    /** Big-endian. */
    fun addU32(value: Long) {
        room(4)
        setU32(size, value)
        size += 4
    }

    /** Overwrites four bytes already added, big-endian. */
    fun setU32(at: Int, value: Long) {
        bytes[at] = (value shr 24).toByte()
        bytes[at + 1] = (value shr 16).toByte()
        bytes[at + 2] = (value shr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    fun take(): ByteArray = bytes.copyOf(size).also { size = 0 }

    /** Hands [block] the bytes so far without copying them, then empties the builder. */
    inline fun <T> drain(block: (bytes: ByteArray, size: Int) -> T): T = block(view(), size).also { clear() }

    @PublishedApi internal fun view() = bytes

    @PublishedApi internal fun clear() {
        size = 0
    }
}
