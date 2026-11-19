package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.withResolvedFiles

/**
 * [attachments] for [block], with each image that doesn't read in place (an iOS Photos-library photo) resolved to a file
 * once, so the thumbnails and the encrypt read that file instead of fetching the photo again each time. The copies are
 * deleted when [block] returns, as for videos; a video keeps its own resolve in the video pipeline.
 */
internal suspend fun <T> FileOperationsProvider.withReadableImages(
    attachments: List<AttachmentInput>,
    block: suspend (List<AttachmentInput>) -> T,
): T {
    val fetchedPerRead = attachments
        .filter { it.contentType.startsWith("image/") && !readsInPlace(it.filePath) }
        .map { it.filePath }
        .distinct()
    if (fetchedPerRead.isEmpty()) return block(attachments)
    return withResolvedFiles(fetchedPerRead) { resolved ->
        val byPath = fetchedPerRead.zip(resolved).toMap()
        block(attachments.map { attachment -> byPath[attachment.filePath]?.let { attachment.copy(filePath = it) } ?: attachment })
    }
}
