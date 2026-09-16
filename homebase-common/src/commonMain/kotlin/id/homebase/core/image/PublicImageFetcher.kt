package id.homebase.core.image

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.common.OdinId
import id.homebase.api.common.PUB_IMAGE_PATH
import okio.Buffer

class PublicImageFetcher(
    private val odinId: OdinId,
    private val options: Options,
    private val contactInfo: ContactInfoGateway
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = contactInfo.avatarBytes(odinId) ?: return null
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
    // Factory<Uri> is the correct type for http URL inputs. (Keyers resolve
    // differently: see CoilKeyerResolutionTest.)
    class Factory(private val contactInfo: ContactInfoGateway) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val odinId = resolveOdinId(data) ?: return null
            return PublicImageFetcher(odinId, options, contactInfo)
        }
    }

    companion object {
        private const val HTTPS_PREFIX = "https://"

        /**
         * The peer [OdinId] behind a Coil `data` parameter, or null when it is not a public-image
         * URL. The `Any` parameter takes both [coil3.Uri] (what Coil hands the Factory) and
         * [String] (test convenience). An exact match: the memory-cache key lives in
         * [PublicAvatarKeyer], so nothing decorates this URL any more.
         */
        internal fun resolveOdinId(data: Any): OdinId? {
            val url = when (data) {
                is Uri -> data.toString()
                is String -> data
                else -> return null
            }
            if (!url.startsWith(HTTPS_PREFIX) || !url.endsWith(PUB_IMAGE_PATH)) return null
            return OdinId(url.removePrefix(HTTPS_PREFIX).removeSuffix(PUB_IMAGE_PATH))
        }
    }
}
