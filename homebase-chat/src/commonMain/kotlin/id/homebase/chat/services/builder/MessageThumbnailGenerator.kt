package id.homebase.chat.services.builder

import id.homebase.api.client.drives.readFileBytes
import id.homebase.api.image.createThumbnails

// Reusable thumbnail creation — shared by ChatAttachmentBuilder and others
object MessageThumbnailGenerator {

    suspend fun generate(
        filePath: String,
        payloadKey: String
    ): ThumbnailResult {
        val bytes = readFileBytes(filePath)

        val (_, tinyThumb, thumbnails) = createThumbnails(bytes, payloadKey)

        return ThumbnailResult(
            preview = tinyThumb,
            thumbnails = thumbnails
        )
    }
}


