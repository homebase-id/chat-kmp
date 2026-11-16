package id.homebase.api.util

/** A growable byte buffer that keeps its capacity between [take]s. */
internal class ByteBuilder(initialCapacity: Int = 1024 * 1024) {
    private var bytes = ByteArray(initialCapacity)
    private var size = 0

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

    fun take(): ByteArray = bytes.copyOf(size).also { size = 0 }
}
