package id.homebase.api.video.hls

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.encryptedPayloadHash
import id.homebase.api.crypto.hashedBy
import id.homebase.api.crypto.newHasher
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.video.SegmentedVideo
import id.homebase.api.video.VideoProgressListener
import id.homebase.api.video.readMoov
import co.touchlab.kermit.Logger
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Cuts an H.264 + AAC MP4 into HLS, encrypted unless [segmentAndEncrypt] gets no key, in one read of the file, as ffmpeg's
 * `-hls_flags single_file -hls_key_info_file` does: one `index.ts` whose byte ranges are segments
 * of about [SEGMENT_SECONDS], each AES-128-CBC encrypted on its own under the payload's key and IV,
 * and an `index.m3u8` addressing them. The payload hash (odin-core docs/payload-hashes.md) is
 * taken from the same pass. Null when the file isn't an MP4 of H.264 and AAC.
 */
internal object HlsSegmenter {
    private const val TAG = "HlsSegmenter"
    private const val SEGMENT_SECONDS = 6.0
    private const val READ_AHEAD = 4
    private const val WRITE_AHEAD = 1
    private const val MAX_HELD_BACK_BYTES = 32L * 1024 * 1024

    /** [segmentAndEncrypt] as a platform's [id.homebase.api.video.VideoCompressor] runs it: failures logged, progress ending at 1. */
    suspend fun segmentForUpload(
        fileOps: FileOperationsProvider,
        inputPath: String,
        keyHeader: KeyHeader?,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? {
        val segmented = try {
            segmentAndEncrypt(fileOps, inputPath, keyHeader, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(tag = TAG, throwable = e) { "HLS segmenting failed" }
            throw e
        }
        if (segmented == null) {
            Logger.e(tag = TAG) { "HLS segmenting: $inputPath isn't an MP4 of H.264 and AAC" }
            return null
        }
        onProgress?.invoke(1f)
        return segmented
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun segmentAndEncrypt(
        fileOps: FileOperationsProvider,
        inputPath: String,
        keyHeader: KeyHeader?,
        onProgress: VideoProgressListener? = null,
    ): SegmentedVideo? {
        val movie = readMoov(fileOps, inputPath)?.let(Mp4Movie::parse) ?: return null
        // Written in decode order across tracks, as ffmpeg interleaves; the file's own order may hold samples back.
        val emitOrder = decodeOrder(movie.samples)
        if (heldBackBytes(movie.samples, emitOrder) > MAX_HELD_BACK_BYTES) return null

        val dir = "${AppCacheDirs.scratchPath(fileOps.getCacheDirectory(), AppCacheDirs.HLS)}/${AppCacheDirs.HLS_DIR_PREFIX}${Uuid.random()}"
        val segmentsPath = "$dir/index.ts"
        val playlistPath = "$dir/index.m3u8"
        val storedHasher = PayloadHashAlgorithm.Blake3.newHasher()
        val contentHasher = PayloadHashAlgorithm.Blake3.newHasher()
        val segments = mutableListOf<Segment>()

        try {
            fileOps.writeStream(segmentsPath, flow {
                val ts = MpegTsWriter(movie.video, movie.audio)
                var segmentStart = movie.start90k
                var written = 0L
                suspend fun finish(end90k: Long) {
                    val plain = ts.drain()
                    if (plain.isEmpty()) return
                    contentHasher.update(plain)
                    val cipher = if (keyHeader != null) AesCbc.encrypt(plain, keyHeader.aesKey, keyHeader.iv) else plain
                    segments += Segment((end90k - segmentStart) / 90_000.0, cipher.size.toLong(), written)
                    written += cipher.size
                    emit(cipher)
                }

                ts.writeTables()
                val held = arrayOfNulls<ByteArray>(movie.samples.size)
                var emitted = 0
                val total = movie.samples.size
                // Read ahead while this cuts, encrypts and writes.
                fileOps.readSamples(inputPath, movie.samples, READ_AHEAD) { next, bytes ->
                    held[next] = bytes
                    while (emitted < total && held[emitOrder[emitted]] != null) {
                        val index = emitOrder[emitted++]
                        val ready = movie.samples[index]
                        // Segments start on a keyframe, as players seek to them.
                        if (ready.track is Mp4Track.Avc && ready.isKey && (ready.pts90k - segmentStart) / 90_000.0 >= SEGMENT_SECONDS) {
                            finish(ready.pts90k)
                            segmentStart = ready.pts90k
                            ts.writeTables()
                        }
                        ts.write(ready, held[index]!!)
                        held[index] = null
                    }
                    if ((next + 1) % PROGRESS_EVERY == 0) onProgress?.invoke((next + 1).toFloat() / total)
                }
                check(emitted == total) { "only $emitted of $total samples were due" }
                finish(movie.end90k)
                // Cut, encrypt and hash the plaintext off the IO thread, which writes and hashes the segment before.
            }.buffer(WRITE_AHEAD).flowOn(Dispatchers.Default).hashedBy(storedHasher))
        } catch (e: Throwable) {
            // A partial index.ts can run to hundreds of MB; ffmpeg's fallback writes its own.
            fileOps.deleteTempFile(segmentsPath)
            throw e
        }

        fileOps.writeStream(playlistPath, flow { emit(playlist(segments, keyHeader?.iv).encodeToByteArray()) })
        return SegmentedVideo(
            playlistPath = playlistPath,
            segmentsPath = segmentsPath,
            hash = keyHeader?.let { encryptedPayloadHash(PayloadHashAlgorithm.Blake3, storedHasher.digest(), contentHasher.digest(), it) },
        )
    }

    private class Segment(val seconds: Double, val length: Long, val offset: Long)

    // The most sample bytes read but not yet due in decode order, at any point of the single pass.
    // By decode time, video first on a tie: (dts, track, index) packed into one sortable Long, as boxed
    // comparators cost hundreds of ms on older phones.
    private fun decodeOrder(samples: List<Mp4Sample>): IntArray {
        val start = samples.minOf { it.dts90k }
        val keys = LongArray(samples.size) { i ->
            val sample = samples[i]
            ((sample.dts90k - start) shl 25) or ((if (sample.track is Mp4Track.Avc) 0L else 1L) shl 24) or i.toLong()
        }
        keys.sort()
        return IntArray(keys.size) { (keys[it] and 0xFFFFFF).toInt() }
    }

    private fun heldBackBytes(samples: List<Mp4Sample>, emitOrder: IntArray): Long {
        val read = BooleanArray(samples.size)
        var emitted = 0
        var held = 0L
        var peak = 0L
        for (i in samples.indices) {
            read[i] = true
            held += samples[i].size
            peak = maxOf(peak, held)
            while (emitted < emitOrder.size && read[emitOrder[emitted]]) held -= samples[emitOrder[emitted++]].size
        }
        return peak
    }

    private fun playlist(segments: List<Segment>, iv: ByteArray?): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:4")
        appendLine("#EXT-X-TARGETDURATION:${segments.maxOf { it.seconds }.roundToInt()}")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        if (iv != null) appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"enc.key\",IV=0x${iv.toHexString()}")
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

    private const val PROGRESS_EVERY = 200
}
