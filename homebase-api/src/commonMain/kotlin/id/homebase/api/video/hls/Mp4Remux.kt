package id.homebase.api.video.hls

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.ByteBuilder
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow

/**
 * Copies an H.264 and AAC MP4's samples unchanged into a new MP4 that holds only those two tracks, so nothing else of
 * the source comes along: no location, capture time, other tracks or metadata. `ftyp`, the samples in one `mdat`, then
 * the `moov` indexing them, as [TsToMp4] writes.
 */
internal object Mp4Remux {
    suspend fun remux(fileOps: FileOperationsProvider, inputPath: String, movie: Mp4Movie, outputPath: String) {
        val table = SampleTable()
        fileOps.writeStream(outputPath, flow {
            emit(SampleTable.head())
            val out = ByteBuilder(TsToMp4.CHUNK)
            fileOps.readSamples(inputPath, movie.samples) { index, bytes ->
                val sample = movie.samples[index]
                table.add(TsSample(sample.track is Mp4Track.Avc, sample.dts90k, sample.pts90k, sample.isKey, bytes.size, bytes))
                out.add(bytes)
                if (out.size >= TsToMp4.CHUNK) emit(out.take())
            }
            if (out.size > 0) emit(out.take())
            emit(table.moov(movie.video, movie.audio))
        })
        fileOps.overwrite(outputPath, SampleTable.MDAT_HEADER_AT, table.mdatHeader())
    }
}

/** Each of [samples], which are in file order, with its bytes, from one read of the file. */
internal suspend fun FileOperationsProvider.readSamples(
    path: String,
    samples: List<Mp4Sample>,
    readAhead: Int = 0,
    onSample: suspend (index: Int, bytes: ByteArray) -> Unit,
) {
    var next = 0
    var position = 0L
    var pending: ByteArray? = null
    var pendingFill = 0
    val chunks = readFileAsFlow(path, READ_CHUNK).let { if (readAhead > 0) it.buffer(readAhead) else it }
    chunks.collect { chunk ->
        var at = 0
        while (at < chunk.size && next < samples.size) {
            val sample = samples[next]
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
            pending = null
            onSample(next++, buffer)
        }
        position += chunk.size
    }
    check(next == samples.size) { "the file ended after $next of ${samples.size} samples" }
}

private const val READ_CHUNK = 256 * 1024
