package id.homebase.core.image

import coil3.fetch.SourceFetchResult
import okio.IOException

/** Up to [byteCount] bytes from the start of the source, read through a peek so whichever decoder takes it gets it whole. */
fun SourceFetchResult.peekHeader(byteCount: Long): ByteArray? = try {
    val peek = source.source().peek()
    peek.request(byteCount)
    peek.readByteArray(minOf(byteCount, peek.buffer.size))
} catch (_: IOException) {
    null
}
