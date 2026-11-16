package id.homebase.api.video.hls

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.encryptedPayloadHash
import id.homebase.api.crypto.newHasher
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.fourCc
import id.homebase.api.image.readU32
import id.homebase.api.image.readU64
import id.homebase.api.video.MAX_MOOV_BYTES
import id.homebase.api.video.SegmentedVideo
import id.homebase.api.video.VideoProgressListener
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Cuts an H.264 + AAC MP4 into encrypted HLS in one read of the file, as ffmpeg's
 * `-hls_flags single_file -hls_key_info_file` does: one `index.ts` whose byte ranges are segments
 * of about [SEGMENT_SECONDS], each AES-128-CBC encrypted on its own under the payload's key and IV,
 * and an `index.m3u8` addressing them. The payload hash (odin-core docs/payload-hashes.md) is
 * taken from the same pass. Null when the file isn't an MP4 of H.264 and AAC.
 */
internal object HlsSegmenter {
    private const val SEGMENT_SECONDS = 6.0
    private const val HASH_QUEUE = 2

    @OptIn(ExperimentalUuidApi::class)
    suspend fun segmentAndEncrypt(
        fileOps: FileOperationsProvider,
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener? = null,
    ): SegmentedVideo? {
        val movie = readMoov(fileOps, inputPath)?.let(Mp4Movie::parse) ?: return null

        val dir = "${AppCacheDirs.scratchPath(fileOps.getCacheDirectory(), AppCacheDirs.HLS)}/${AppCacheDirs.HLS_DIR_PREFIX}${Uuid.random()}"
        val segmentsPath = "$dir/index.ts"
        val playlistPath = "$dir/index.m3u8"
        val storedHasher = PayloadHashAlgorithm.Blake3.newHasher()
        val contentHasher = PayloadHashAlgorithm.Blake3.newHasher()
        val segments = mutableListOf<Segment>()

        // Each hash runs on its own core, fed segment by segment, so hashing overlaps cutting and encrypting.
        coroutineScope {
            val toContentHash = Channel<ByteArray>(HASH_QUEUE)
            val toStoredHash = Channel<ByteArray>(HASH_QUEUE)
            val hashing = listOf(contentHasher to toContentHash, storedHasher to toStoredHash).map { (hasher, queue) ->
                launch(Dispatchers.Default) { for (bytes in queue) hasher.update(bytes) }
            }
            fileOps.writeStream(segmentsPath, flow {
                val ts = MpegTsWriter(movie.video, movie.audio)
                var segmentStart = movie.start90k
                var written = 0L
                suspend fun finish(end90k: Long) {
                    val plain = ts.drain()
                    if (plain.isEmpty()) return
                    toContentHash.send(plain)
                    val cipher = AesCbc.encrypt(plain, keyHeader.aesKey, keyHeader.iv)
                    toStoredHash.send(cipher)
                    segments += Segment((end90k - segmentStart) / 90_000.0, cipher.size.toLong(), written)
                    written += cipher.size
                    emit(cipher)
                }

                ts.writeTables()
                var next = 0
                var position = 0L
                var pending: ByteArray? = null
                var pendingFill = 0
                val total = movie.samples.size
                fileOps.readFileAsFlow(inputPath, CHUNK).collect { chunk ->
                    var at = 0
                    while (at < chunk.size && next < total) {
                        val sample = movie.samples[next]
                        if (pending == null) {
                            val skip = sample.offset - (position + at)
                            if (skip > 0) {
                                at += minOf(skip, (chunk.size - at).toLong()).toInt()
                                continue
                            }
                            pending = ByteArray(sample.size)
                            pendingFill = 0
                        }
                        val buffer = pending!!
                        val n = minOf(buffer.size - pendingFill, chunk.size - at)
                        chunk.copyInto(buffer, pendingFill, at, at + n)
                        pendingFill += n
                        at += n
                        if (pendingFill < buffer.size) continue
                        // Segments start on a keyframe, as players seek to them.
                        if (sample.track is Mp4Track.Avc && sample.isKey && (sample.pts90k - segmentStart) / 90_000.0 >= SEGMENT_SECONDS) {
                            finish(sample.pts90k)
                            segmentStart = sample.pts90k
                            ts.writeTables()
                        }
                        ts.write(sample, buffer)
                        pending = null
                        next++
                        if (next % PROGRESS_EVERY == 0) onProgress?.invoke(next.toFloat() / total)
                    }
                    position += chunk.size
                }
                check(next == total) { "the file ended after $next of $total samples" }
                finish(movie.end90k)
            })
            toContentHash.close()
            toStoredHash.close()
            hashing.joinAll()
        }

        fileOps.writeStream(playlistPath, flow { emit(playlist(segments, keyHeader.iv).encodeToByteArray()) })
        return SegmentedVideo(
            playlistPath = playlistPath,
            segmentsPath = segmentsPath,
            hash = encryptedPayloadHash(PayloadHashAlgorithm.Blake3, storedHasher.digest(), contentHasher.digest(), keyHeader),
        )
    }

    private class Segment(val seconds: Double, val length: Long, val offset: Long)

    private fun playlist(segments: List<Segment>, iv: ByteArray): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:4")
        appendLine("#EXT-X-TARGETDURATION:${segments.maxOf { it.seconds }.roundToInt()}")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"enc.key\",IV=0x${iv.toHexString()}")
        for (segment in segments) {
            appendLine("#EXTINF:${sixDecimals(segment.seconds)},")
            appendLine("#EXT-X-BYTERANGE:${segment.length}@${segment.offset}")
            appendLine("index.ts")
        }
        appendLine("#EXT-X-ENDLIST")
    }

    private fun sixDecimals(seconds: Double): String {
        val micros = (seconds * 1_000_000).roundToLong()
        return "${micros / 1_000_000}.${(micros % 1_000_000).toString().padStart(6, '0')}"
    }

    // Walks the top-level boxes by their headers alone, so a moov after the media costs no pass over it.
    private suspend fun readMoov(fileOps: FileOperationsProvider, path: String): ByteArray? {
        val size = fileOps.getFileSize(path)
        var at = 0L
        while (at + 8 <= size) {
            val header = fileOps.readFileRange(path, at, 16)
            if (header.size < 8) return null
            var boxSize = readU32(header, 0, little = false).toLong() and 0xFFFFFFFFL
            var headerSize = 8
            if (boxSize == 1L && header.size >= 16) {
                headerSize = 16
                boxSize = readU64(header, 8)
            } else if (boxSize == 0L) {
                boxSize = size - at
            }
            if (boxSize < headerSize) return null
            if (fourCc(header, 4) == "moov") {
                if (boxSize > MAX_MOOV_BYTES) return null
                return fileOps.readFileRange(path, at, boxSize.toInt()).copyOfRange(headerSize, boxSize.toInt())
            }
            at += boxSize
        }
        return null
    }

    private const val CHUNK = 256 * 1024
    private const val PROGRESS_EVERY = 200
}
