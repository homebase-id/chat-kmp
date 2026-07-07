package id.homebase.core.image

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import id.homebase.api.client.profile.PublicProfileProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.PUB_IMAGE_PATH
import okio.Buffer

class PublicImageFetcher(
    private val odinId: OdinId,
    private val options: Options,
    private val provider: PublicProfileProvider
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = provider.getPublicImage(odinId) ?: return null
        val buffer = Buffer().write(bytes)
        return SourceFetchResult(
            source = ImageSource(buffer, options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    // Factory<Uri> — Coil 3 maps a String model to coil3.Uri BEFORE
    // Fetcher-factory matching runs. A Factory<String> is therefore never
    // polled for http(s) URLs (Coil sees only Uris at that stage), falls
    // through to the built-in NetworkFetcher, and our cache layer is
    // bypassed. Factory<Any> wasn't polled either (verified empirically —
    // diagnostic logs showed Factory<Any>.create fired for custom data
    // classes like HomebaseImageData but never for a mapped http Uri).
    // Factory<Uri> is the correct type for http URL inputs.
    class Factory(private val provider: PublicProfileProvider) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val odinId = resolveOdinId(data) ?: return null
            return PublicImageFetcher(odinId, options, provider)
        }
    }

    companion object {
        /**
         * Extracts the peer [OdinId] from a Coil request `data` parameter when
         * it represents a public-image URL, or returns null otherwise.
         *
         * The `Any` parameter makes the helper directly testable with both
         * [coil3.Uri] (production path, what Coil hands the Factory) and
         * [String] (convenience in tests, avoids standing up a real Coil
         * pipeline). Tested in `PublicImageFetcherFactoryTest` — keep this
         * helper and its tests in sync if the URL shape changes.
         *
         * Strips a trailing `?...` query string before matching — callers that need the image
         * to actually reload after it changes server-side (e.g. the owner's own avatar after a
         * new upload) append a cache-busting `?v=<lastModified>` so Coil treats it as a distinct
         * request/cache-key; the underlying odinId/path shape is unaffected.
         */
        internal fun resolveOdinId(data: Any): OdinId? {
            val url = when (data) {
                is Uri -> data.toString()
                is String -> data
                else -> return null
            }.substringBefore('?')
            if (!url.contains(PUB_IMAGE_PATH)) return null
            return OdinId(
                url.removePrefix("https://").removeSuffix(PUB_IMAGE_PATH)
            )
        }
    }
}
