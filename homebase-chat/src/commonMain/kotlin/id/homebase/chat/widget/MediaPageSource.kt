package id.homebase.chat.widget

import id.homebase.chat.services.LocalAttachmentContext

/**
 * Which image source the full-screen viewer should use for a page.
 *
 * The payload, as sent, whenever it can be had: once its IV decodes, or for an unencrypted file (a public feed
 * post) whose payloads carry no IV at all. The sender's own copy is in the payload cache already (seeded at send),
 * so it shows what everyone else gets, with no download. Only while a send is still uploading does the local file
 * picked for it ([LocalAttachmentContext]) stand in. On an *encrypted* file with no local file, a missing IV means
 * the payload isn't ready yet ([Pending]); an IV that is present but won't decode means it's corrupt ([Unavailable]).
 */
internal sealed interface MediaPageSource {
    data class LocalFile(val path: String) : MediaPageSource

    /** @param iv null for a plaintext payload — fetch the bytes and render them undecrypted. */
    class Remote(val iv: ByteArray?) : MediaPageSource
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
    val localPath = (localContext as? LocalAttachmentContext.Image)?.localFilePath
    return when {
        decodedIv != null -> MediaPageSource.Remote(decodedIv)
        !isEncrypted -> MediaPageSource.Remote(iv = null) // plaintext post → no IV, not pending
        localPath != null -> MediaPageSource.LocalFile(localPath)
        rawIvPresent -> MediaPageSource.Unavailable // IV present but won't decode → corrupt
        else -> MediaPageSource.Pending // no IV yet → still uploading / not ready
    }
}
