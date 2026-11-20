package id.homebase.api.video.hls

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.ByteBuilder
import id.homebase.api.video.VideoProgressListener
import kotlinx.coroutines.flow.flow

/**
 * Copies an H.264 and AAC MP4's samples into a new MP4 that holds only those two tracks, so nothing else of the source
 * comes along: no location, capture time, other tracks or metadata, and no SEI messages, where some cameras put GPS.
 * `ftyp`, the samples in one `mdat`, then the `moov` indexing them, as [TsToMp4] writes.
 */
internal object Mp4Remux {
    suspend fun remux(fileOps: FileOperationsProvider, inputPath: String, movie: Mp4Movie, outputPath: String, onProgress: VideoProgressListener? = null) {
        val table = SampleTable()
        fileOps.writeStream(outputPath, flow {
            emit(SampleTable.head())
            val out = ByteBuilder(TsToMp4.CHUNK)
            fileOps.readSamples(inputPath, movie.samples) { index, read ->
                val sample = movie.samples[index]
                val video = sample.track is Mp4Track.Avc
                val bytes = if (video) withoutSei(read) else read
                table.add(TsSample(video, sample.dts90k, sample.pts90k, sample.isKey, bytes.size, bytes))
                out.add(bytes)
                if (out.size >= TsToMp4.CHUNK) emit(out.take())
                if ((index + 1) % PROGRESS_EVERY == 0) onProgress?.invoke((index + 1).toFloat() / movie.samples.size)
            }
            if (out.size > 0) emit(out.take())
            emit(table.moov(movie.video, movie.audio))
        })
        fileOps.overwrite(outputPath, SampleTable.MDAT_HEADER_AT, table.mdatHeader())
    }

    /** [sample]'s NAL units, 4-byte length-prefixed, without its SEI units. */
    fun withoutSei(sample: ByteArray): ByteArray {
        val kept = ArrayList<IntRange>()
        var at = 0
        while (at < sample.size) {
            check(at + 4 < sample.size) { "a NAL length runs past its sample" }
            val length = ((sample[at].toInt() and 0xFF) shl 24) or ((sample[at + 1].toInt() and 0xFF) shl 16) or
                ((sample[at + 2].toInt() and 0xFF) shl 8) or (sample[at + 3].toInt() and 0xFF)
            val end = at + 4 + length
            check(length > 0 && end <= sample.size) { "a NAL unit runs past its sample" }
            if ((sample[at + 4].toInt() and 0x1F) != NAL_SEI) kept += at until end
            at = end
        }
        val size = kept.sumOf { it.last - it.first + 1 }
        if (size == sample.size) return sample
        val out = ByteArray(size)
        var o = 0
        for (range in kept) {
            sample.copyInto(out, o, range.first, range.last + 1)
            o += range.last - range.first + 1
        }
        return out
    }

    private const val NAL_SEI = 6
    private const val PROGRESS_EVERY = 200
}
