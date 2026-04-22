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
         */
        internal fun resolveOdinId(data: Any): OdinId? {
            val url = when (data) {
                is Uri -> data.toString()
                is String -> data
                else -> return null
            }
            if (!url.contains("/pub/image")) return null
            return OdinId(
                url.removePrefix("https://").removeSuffix("/pub/image")
            )
        }
    }
}
