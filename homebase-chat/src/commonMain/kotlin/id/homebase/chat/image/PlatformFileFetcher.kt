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
import okio.Path.Companion.toPath

/**
 * Coil fetcher for a picked [PlatformFile], so composer/attachment previews can pass the
 * `PlatformFile` itself as the Coil `model`.
 *
 * A file with a readable local path is handed to Coil as a file-backed source, so decoders can
 * read it in place (iOS ImageIO maps it) instead of copying the whole file into memory. Anything
 * else (an Android `content://` URI, a browser `File`, which has no path) is read with FileKit's
 * cross-platform `readBytes()`.
 */
class PlatformFileFetcher(
    private val file: PlatformFile,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val path = file.localPathOrNull()
        val source = if (path != null) {
            ImageSource(file = path.toPath(), fileSystem = options.fileSystem)
        } else {
            ImageSource(Buffer().apply { write(file.readBytes()) }, options.fileSystem)
        }
        return SourceFetchResult(source = source, mimeType = null, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<PlatformFile> {
        override fun create(data: PlatformFile, options: Options, imageLoader: ImageLoader): Fetcher =
            PlatformFileFetcher(data, options)
    }
}

/**
 * A local filesystem path holding this file's bytes, readable now without any extra grant, or
 * null when there is none (Android `content://` URI, iOS URL outside its security scope, web).
 */
internal expect fun PlatformFile.localPathOrNull(): String?
