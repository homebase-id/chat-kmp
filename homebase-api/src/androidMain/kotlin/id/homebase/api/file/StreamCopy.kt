package id.homebase.api.file

import java.io.InputStream
import java.io.OutputStream

// A content:// stream is served by another process, one round trip per read: Java's 8 KB default copied a
// 210 MB video 3-6x slower on a Pixel 5 (MEDIA_BENCHMARKS.md).
const val LARGE_COPY_BUFFER = 1 shl 20

fun InputStream.copyInLargeChunks(out: OutputStream): Long = copyTo(out, LARGE_COPY_BUFFER)
