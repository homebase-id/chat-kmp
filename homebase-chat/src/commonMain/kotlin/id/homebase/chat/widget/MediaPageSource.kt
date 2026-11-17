package id.homebase.chat.widget

import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.localImageOnly
import id.homebase.chat.services.imageStandIn

/**
 * Which image source the full-screen viewer should use for a page.
 *
 * The payload, as sent, whenever it can be requested: once its IV decodes, or for an unencrypted file (a public feed
 * post) whose payloads carry no IV at all. The sender sees what everyone else gets, usually from the payload cache
 * seeded at send; their local file ([LocalAttachmentContext]) is the [Remote.standIn] if the sent copy can't be loaded
 * yet, and the source itself while an encrypted send has no IV. On an *encrypted* file with no local file, a missing
 * IV means the payload isn't ready yet ([Pending]); an IV that is present but won't decode means it's corrupt
 * ([Unavailable]).
 */
internal sealed interface MediaPageSource {
    data class LocalFile(val path: String) : MediaPageSource

    /** @param iv null for a plaintext payload — fetch the bytes and render them undecrypted. */
    class Remote(val iv: ByteArray?, val standIn: String? = null) : MediaPageSource
    data object Pending : MediaPageSource
    data object Unavailable : MediaPageSource
}

/**
 * Pure decision for [MediaPageSource], extracted from the composable so the
 * sent-over-local precedence (and the pending-vs-corrupt distinction) is
 * unit-testable.
 *
 * A [LocalAttachmentContext.Video] is intentionally ignored here — only an
 * [LocalAttachmentContext.Image] local file can serve as an image tile source.
 *
 * @param localContext the per-(message, payload) local attachment, if any
 * @param rawIvPresent whether the payload carries an IV at all (on an encrypted
 *   file, false ⇒ not yet uploaded / pending)
 * @param decodedIv the decoded payload IV, or null if absent or undecodable
 * @param isEncrypted false for a public feed post, whose payloads carry no IV at all
 */
internal fun resolveMediaPageSource(
    localContext: LocalAttachmentContext?,
    rawIvPresent: Boolean,
    decodedIv: ByteArray?,
    isEncrypted: Boolean = true,
): MediaPageSource {
    val standIn = localContext.imageStandIn
    val localOnly = localContext.localImageOnly(isEncrypted, ivPresent = decodedIv != null)
    return when {
        localOnly != null -> MediaPageSource.LocalFile(localOnly)
        decodedIv != null -> MediaPageSource.Remote(decodedIv, standIn)
        !isEncrypted -> MediaPageSource.Remote(iv = null, standIn = standIn) // plaintext post → no IV, not pending
        rawIvPresent -> MediaPageSource.Unavailable // IV present but won't decode → corrupt
        else -> MediaPageSource.Pending // no IV yet → still uploading / not ready
    }
}
