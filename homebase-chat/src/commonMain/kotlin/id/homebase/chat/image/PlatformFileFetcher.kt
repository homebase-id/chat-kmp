package id.homebase.chat.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import okio.Buffer

/**
 * Coil fetcher for a picked [PlatformFile], so composer/attachment previews can pass the
 * `PlatformFile` itself as the Coil `model`.
 *
 * This is what makes previews render on the web: a browser-picked `PlatformFile` has no
 * filesystem path (FileKit's `PlatformFile.path` is non-web only), so the old `file.toString()`
 * model was an unloadable string → blank thumbnail. Reading bytes via FileKit's cross-platform
 * `readBytes()` works on every target (on web it reads the underlying browser `File`).
 */
class PlatformFileFetcher(
    private val file: PlatformFile,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bytes = file.readBytes()
        return SourceFetchResult(
            source = ImageSource(Buffer().apply { write(bytes) }, options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<PlatformFile> {
        override fun create(data: PlatformFile, options: Options, imageLoader: ImageLoader): Fetcher =
            PlatformFileFetcher(data, options)
    }
}
