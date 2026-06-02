package id.homebase.chat.widget

import id.homebase.chat.services.LocalAttachmentContext

/**
 * Which image source the full-screen viewer should use for a page.
 *
 * A locally-available original (an image sent this session, held by
 * [LocalAttachmentContext]) is preferred over the remote payload: it renders
 * full-resolution straight from disk with no drive fetch and no decrypt. The
 * remote payload is used when there is no local file but the payload IV decodes.
 * A missing IV means the payload isn't ready yet ([Pending], like a still-
 * uploading attachment); an IV that is present but won't decode means the
 * payload is corrupt ([Unavailable]).
 */
internal sealed interface MediaPageSource {
    data class LocalFile(val path: String) : MediaPageSource
    class Remote(val iv: ByteArray) : MediaPageSource
    data object Pending : MediaPageSource
    data object Unavailable : MediaPageSource
}

/**
 * Pure decision for [MediaPageSource], extracted from the composable so the
 * local-over-remote precedence (and the pending-vs-corrupt distinction) is
 * unit-testable.
 *
 * A [LocalAttachmentContext.Video] is intentionally ignored here — only an
 * [LocalAttachmentContext.Image] local file can serve as an image tile source.
 *
 * @param localContext the per-(message, payload) local attachment, if any
 * @param rawIvPresent whether the payload carries an IV at all (false ⇒ not yet
 *   uploaded / pending)
 * @param decodedIv the decoded payload IV, or null if absent or undecodable
 */
internal fun resolveMediaPageSource(
    localContext: LocalAttachmentContext?,
    rawIvPresent: Boolean,
    decodedIv: ByteArray?,
): MediaPageSource {
    val localPath = (localContext as? LocalAttachmentContext.Image)?.localFilePath
    return when {
        localPath != null -> MediaPageSource.LocalFile(localPath)
        decodedIv != null -> MediaPageSource.Remote(decodedIv)
        rawIvPresent -> MediaPageSource.Unavailable // IV present but won't decode → corrupt
        else -> MediaPageSource.Pending // no IV yet → still uploading / not ready
    }
}
