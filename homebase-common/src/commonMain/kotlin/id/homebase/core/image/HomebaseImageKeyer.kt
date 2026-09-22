package id.homebase.core.image

import coil3.key.Keyer
import coil3.request.Options

/**
 * Memory cache keyer for Homebase images.
 *
 * Generates predictable cache keys so thumbnails loaded in the chat list
 * can be reused as placeholders when opening the full-screen viewer.
 *
 * Thumbnail keys are size-independent: all thumbnail sizes for the same
 * image share one memory cache entry (the most recently loaded wins).
 */
class HomebaseImageKeyer : Keyer<HomebaseImageData> {

    override fun key(data: HomebaseImageData, options: Options): String? {
        if (data.isPending) return null
        val variant = if (data.loadFullPayload) "full" else "thumb"
        return "homebase/${identity(data)}${data.driveId}/${data.fileId}/${data.payloadKey}/$variant/${data.lastModified ?: 0}"
    }

    companion object {
        /** Build the memory cache key for the thumbnail variant of an image. */
        fun thumbnailCacheKey(data: HomebaseImageData): String =
            "homebase/${identity(data)}${data.driveId}/${data.fileId}/${data.payloadKey}/thumb/${data.lastModified ?: 0}"

        // Peer images share driveId/fileId space with local ones, so the author identity must be in
        // the key or an over-peer variant would collide with a same-id local entry. Empty for local.
        private fun identity(data: HomebaseImageData): String =
            data.remoteOdinId?.let { "$it@" } ?: ""
    }
}
