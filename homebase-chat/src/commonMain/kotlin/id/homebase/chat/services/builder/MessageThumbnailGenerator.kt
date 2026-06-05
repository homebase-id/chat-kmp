package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.createThumbnails

object MessageThumbnailGenerator {

    suspend fun generate(
        filePath: String,
        payloadKey: String,
        fileOperationsProvider: FileOperationsProvider,
    ): ThumbnailResult {
        val bytes = fileOperationsProvider.readFileBytes(filePath)

        val (_, tinyThumb, thumbnails) = createThumbnails(bytes, payloadKey)

        return ThumbnailResult(
            preview = tinyThumb,
            thumbnails = thumbnails,
            sourceBytes = bytes
        )
    }
}


