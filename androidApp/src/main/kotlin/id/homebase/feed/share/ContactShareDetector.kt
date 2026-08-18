package id.homebase.feed.share

import co.touchlab.kermit.Logger
import id.homebase.chat.contactcard.VCardContact
import id.homebase.chat.contactcard.VCardParser
import java.io.File

/**
 * Recognises a contact share and turns it into a [VCardContact].
 *
 * Both delivery shapes are handled: a `.vcf` via `EXTRA_STREAM` (already copied to the share
 * temp dir by [SharedContentExtractor]) and a vCard body via `EXTRA_TEXT`. Returns null for
 * anything that isn't a contact, or for a vCard the parser couldn't make sense of — the caller
 * then sends the raw file/text as before, so a share is never dropped.
 *
 * A multi-contact `.vcf` yields its FIRST block; the rest are logged and ignored.
 */
object ContactShareDetector {

    private const val TAG = "ContactShare"

    /** Bigger than any plausible vCard; stops a mislabelled 500 MB file being slurped into RAM. */
    private const val MAX_VCARD_BYTES = 1024L * 1024

    private val VCARD_MIME_TYPES = setOf(
        "text/vcard",
        "text/x-vcard",
        // Pre-RFC-6350 exporters still emit text/directory for a vCard.
        "text/directory",
    )

    fun detect(intentMimeType: String?, content: SharedContent): VCardContact? =
        detect(intentMimeType, content) { file -> readVCardFile(file.path) }

    internal fun detect(
        intentMimeType: String?,
        content: SharedContent,
        readFile: (SharedFile) -> String?,
    ): VCardContact? {
        val vcardFile = content.files.firstOrNull { it.isVCard() }
        val source = when {
            vcardFile != null -> readFile(vcardFile)
            // Some senders put the card body straight in EXTRA_TEXT. Trust the mime type,
            // or the BEGIN:VCARD marker when the sender declared text/plain.
            isVCardMime(intentMimeType) || VCardParser.looksLikeVCard(content.text) -> content.text
            else -> null
        } ?: return null

        if (!VCardParser.looksLikeVCard(source)) return null

        val cards = VCardParser.parse(source)
        if (cards.isEmpty()) {
            Logger.w(tag = TAG) { "vCard detected but nothing parsed; falling back to raw share" }
            return null
        }
        if (cards.size > 1) {
            Logger.i(tag = TAG) { "vCard carries ${cards.size} blocks; sending the first only" }
        }
        return cards.first()
    }

    private fun SharedFile.isVCard(): Boolean =
        isVCardMime(mimeType) || displayName.endsWith(".vcf", ignoreCase = true)

    private fun isVCardMime(mimeType: String?): Boolean =
        mimeType?.substringBefore(';')?.trim()?.lowercase() in VCARD_MIME_TYPES

    private fun readVCardFile(path: String): String? = try {
        val file = File(path)
        if (file.length() > MAX_VCARD_BYTES) null else file.readText()
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "Failed to read shared vCard" }
        null
    }
}
