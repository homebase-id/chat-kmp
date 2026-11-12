package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.files.toUploadPath
import id.homebase.core.util.contentType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name

/**
 * Resolve a picked (already materialized) image [PlatformFile] into an
 * [AttachmentInput] for [MessageAttachmentBuilder]. Mirrors the FileImage branch
 * of MessageActionsHandler.addMessageWithFiles: real upload path + content-type
 * detection. Caller adds forceSticker via copy() if needed.
 *
 * [sourceContentType] is the type read off the ORIGINAL picked handle (see
 * [PlatformFile.contentType]). Pass it whenever the receiver is a pick-time sandbox
 * copy: the copy's name may carry no extension, and this receiver can otherwise only be
 * typed from that name (#1149).
 */
suspend fun PlatformFile.toImageAttachmentInput(
    fileOps: FileOperationsProvider,
    sourceContentType: String? = null,
): AttachmentInput {
    return AttachmentInput(
        filePath = toUploadPath(fileOps),
        contentType = sourceContentType ?: contentType(),
        displayName = name,
    )
}
