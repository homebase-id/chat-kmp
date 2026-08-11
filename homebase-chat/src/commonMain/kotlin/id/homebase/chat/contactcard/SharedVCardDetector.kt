package id.homebase.chat.contactcard

import co.touchlab.kermit.Logger
import id.homebase.core.share.SharedContentDescriptor
import kotlin.coroutines.cancellation.CancellationException

/**
 * Recognises a contact share that arrived as a [SharedContentDescriptor] and turns it into a
 * [VCardContact].
 *
 * This is the non-Android delivery shape: the iOS share extension writes the descriptor to the
 * App Group, and `ShareCacheStorage` has a jvmMain actual too. A `.vcf` misses the extension's
 * image/movie/url/plainText branches — `public.vcard` conforms to `public.text`, not
 * `public.plain-text` — so it lands as a generic `FILE` with `mimeTypes = ["text/vcard"]`.
 *
 * Semantics mirror Android's `ContactShareDetector`. Returns null for anything that isn't a
 * parseable contact; the caller then sends the raw file/text as before, so a share is never
 * dropped. A multi-contact `.vcf` yields its FIRST block; the rest are logged and ignored.
 */
object SharedVCardDetector {

    private const val TAG = "ContactShare"

    /** Bigger than any plausible vCard; stops a mislabelled 500 MB file being slurped into RAM. */
    const val MAX_VCARD_BYTES = 1024L * 1024

    private val VCARD_MIME_TYPES = setOf(
        "text/vcard",
        "text/x-vcard",
        // Pre-RFC-6350 exporters still emit text/directory for a vCard.
        "text/directory",
    )

    suspend fun detect(
        descriptor: SharedContentDescriptor,
        fileSize: (fileName: String) -> Long,
        readFileText: suspend (fileName: String) -> String,
    ): VCardContact? {
        // A lone vcf only: the contact card replaces the whole share, so anything shared
        // alongside it would be silently dropped.
        val vCardFile = descriptor.fileNames.singleOrNull()?.takeIf {
            isVCardMime(descriptor.mimeTypes.firstOrNull()) || it.endsWith(".vcf", ignoreCase = true)
        }

        val source = when {
            vCardFile != null -> readCapped(vCardFile, fileSize, readFileText)
            // Some senders put the card body straight in the share text.
            descriptor.fileNames.isEmpty() -> descriptor.text
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

    private suspend fun readCapped(
        fileName: String,
        fileSize: (String) -> Long,
        readFileText: suspend (String) -> String,
    ): String? = try {
        if (fileSize(fileName) > MAX_VCARD_BYTES) {
            Logger.w(tag = TAG) { "Shared vCard exceeds $MAX_VCARD_BYTES bytes; sending it as a file" }
            null
        } else {
            readFileText(fileName)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "Failed to read shared vCard" }
        null
    }

    private fun isVCardMime(mimeType: String?): Boolean =
        mimeType?.substringBefore(';')?.trim()?.lowercase() in VCARD_MIME_TYPES
}

/**
 * Normalizes a parsed vCard into the wire descriptor. Implemented in homebase-core, which owns
 * `ContactFieldValidation` (E.164) — homebase-chat does not depend on it, so the send path takes
 * the conversion by injection.
 */
fun interface VCardDescriptorFactory {
    fun toDescriptor(contact: VCardContact): ContactCardDescriptor?
}
