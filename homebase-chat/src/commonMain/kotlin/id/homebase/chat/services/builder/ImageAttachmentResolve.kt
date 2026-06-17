package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.chat.conversationlist.toUploadPath
import id.homebase.core.util.resolveContentType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name

/**
 * Resolve a picked (already materialized) image [PlatformFile] into an
 * [AttachmentInput] for [MessageAttachmentBuilder]. Mirrors the FileImage branch
 * of MessageActionsHandler.addMessageWithFiles: real upload path + content-type
 * detection + HEIC->JPEG transcode (iPhone photos default to HEIC). Caller adds
 * forceSticker via copy() if needed.
 */
suspend fun PlatformFile.toImageAttachmentInput(fileOps: FileOperationsProvider): AttachmentInput {
    var filePath = toUploadPath(fileOps)
    var contentType = resolveContentType(fileName = name, platformMimeType = mimeType()?.toString())
    if (contentType == "image/heic" || contentType == "image/heif") {
        val heicBytes = fileOps.readFileBytes(filePath)
        val jpegBytes = convertHeicToJpeg(heicBytes)
        if (jpegBytes != null) {
            filePath = fileOps.writeBytesToTempFile(jpegBytes, "heic_converted_", ".jpg")
            contentType = "image/jpeg"
        }
    }
    return AttachmentInput(filePath = filePath, contentType = contentType, displayName = name)
}
