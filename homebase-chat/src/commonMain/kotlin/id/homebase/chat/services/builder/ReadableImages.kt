package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.withResolvedFiles

/**
 * [attachments] for [block], with each iOS Photos-library photo resolved to a file once, so the thumbnails and the
 * encrypt read that file instead of fetching the photo again each time. The copies are deleted when [block] returns,
 * as for videos; a video keeps its own resolve in the video pipeline.
 */
internal suspend fun <T> FileOperationsProvider.withReadableImages(
    attachments: List<AttachmentInput>,
    block: suspend (List<AttachmentInput>) -> T,
): T {
    val toResolve = attachments
        // Not gated on image/: a RAW pick (a ProRAW .DNG) is typed from its name as octet-stream.
        .filter { isPhotoLibraryAsset(it.filePath) && !it.contentType.startsWith("video/") }
        .map { it.filePath }
        .distinct()
    if (toResolve.isEmpty()) return block(attachments)
    return withResolvedFiles(toResolve) { resolved ->
        val byPath = toResolve.zip(resolved).toMap()
        block(attachments.map { attachment -> byPath[attachment.filePath]?.let { attachment.copy(filePath = it) } ?: attachment })
    }
}
