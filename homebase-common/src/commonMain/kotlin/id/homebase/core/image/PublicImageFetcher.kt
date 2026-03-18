package id.homebase.core.image

import coil3.ImageLoader
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

    class Factory(private val provider: PublicProfileProvider) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.contains("/pub/image")) return null
            val odinId = OdinId(
                data.removePrefix("https://").removeSuffix("/pub/image")
            )
            return PublicImageFetcher(odinId, options, provider)
        }
    }
}
