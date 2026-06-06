package id.homebase.core.share

import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.clipboard.platformFileFromPath
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Wrap a shared media file (already copied to a readable path) as the editor's
 * per-attachment model, chosen by MIME prefix.
 *
 * Shared by the share-into-chat and share-into-"New Moment" hand-offs so both
 * platforms produce identical attachments for the chat composer and the moments
 * composer. The bare image/video metadata (EXIF, video poster/duration) is
 * backfilled later by whichever editor consumes the attachment — see
 * `MomentComposeViewModel`'s init backfill and `AttachmentHandler`.
 */
@OptIn(ExperimentalUuidApi::class)
fun sharedMediaAttachment(path: String, mimeType: String): AttachmentPendingFile {
    val file = platformFileFromPath(path)
    return when {
        mimeType.startsWith("image/") ->
            AttachmentPendingFile.FileImage(Uuid.random(), file)
        mimeType.startsWith("video/") ->
            AttachmentPendingFile.FileVideo(Uuid.random(), file, thumbnailBytes = null)
        else ->
            AttachmentPendingFile.File(Uuid.random(), file)
    }
}
