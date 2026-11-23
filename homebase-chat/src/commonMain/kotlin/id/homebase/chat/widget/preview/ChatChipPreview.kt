package id.homebase.chat.widget.preview

import id.homebase.core.pdf.pdfPageViewerAvailable
import id.homebase.api.file.extensionForContentType
import id.homebase.core.util.isWeb
import kotlin.uuid.Uuid

/** What a tap on a chat file chip previews, or null to download it: chat already shows images and plays videos inline. */
internal fun chatChipPreviewKind(
    contentType: String?,
    name: String,
    sizeBytes: Long?,
    web: Boolean = isWeb(),
    pdfAvailable: Boolean = pdfPageViewerAvailable,
): FilePreviewKind? =
    filePreviewKind(contentType, name, sizeBytes, web, pdfAvailable)?.takeIf { it == FilePreviewKind.Pdf || it == FilePreviewKind.Text || it == FilePreviewKind.Markdown }

/** A name for the decrypted copy that is unique to the payload and safe in a URI; the extension is the bytes' own. */
internal fun decryptedCopyName(fileId: Uuid, payloadKey: String, contentType: String?): String =
    "preview_${fileId}_${payloadKey.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")}.${extensionForContentType(contentType)}"
