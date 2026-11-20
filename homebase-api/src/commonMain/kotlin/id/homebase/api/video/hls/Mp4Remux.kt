package id.homebase.api.video.hls

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.ByteBuilder
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
