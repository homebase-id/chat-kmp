package id.homebase.chat.widget.preview

import id.homebase.core.pdf.pdfPageViewerAvailable
import id.homebase.core.util.extensionForMimeType
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
    filePreviewKind(contentType, name, sizeBytes, web, pdfAvailable)?.takeIf { it == FilePreviewKind.Pdf || it == FilePreviewKind.Text }

/** A name for the decrypted copy that is unique to the payload and safe in a URI; the sender's name only lends its extension. */
internal fun decryptedCopyName(fileId: Uuid, payloadKey: String, senderName: String?, contentType: String?): String {
    val ext = senderName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 10 && it.all(Char::isLetterOrDigit) }
        ?: contentType?.let { extensionForMimeType(it) ?: it.substringAfter('/', "").takeIf { s -> s.isNotEmpty() && s.length <= 10 && s.all(Char::isLetterOrDigit) } }
        ?: "bin"
    return "preview_${fileId}_${payloadKey.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")}.$ext"
}
