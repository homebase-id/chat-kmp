package id.homebase.chat.widget

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.services.ChatProtocol

fun List<PayloadDescriptor>?.mediaPayloads(): List<PayloadDescriptor> =
    this?.filter { payload ->
        payload.key != ChatProtocol.DefaultPayloadKey &&
            !payload.key.startsWith(ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY)
    }.orEmpty()

// mediaPayloads() minus PAYLOAD_KEY_LINKS: a link preview uploads with an image content-type
// (so it passes isVisualMedia()) but no THUMB_SMALL/MEDIUM thumbnails, so a reply quote asking
// the drive for one gets a broken image (#1708). Scoped to reply quotes only — the message's
// own bubble still renders the link card through mediaPayloads() via MediaMessage/MediaItem.
fun List<PayloadDescriptor>?.replyQuoteMediaPayloads(): List<PayloadDescriptor> =
    mediaPayloads().filter { it.key != ChatProtocol.PAYLOAD_KEY_LINKS }

/**
 * Resolves the display name for a reply quote's author.
 *
 * @param authorOdinId Raw domain from [ReplyPreview.authorOdinId].
 * @param currentOdinId Current user's domain (empty if unavailable).
 * @param resolvedDisplayName Display name from the original message, if loaded.
 * @param youLabel Localized "You" string.
 */
fun resolveReplyAuthorName(
    authorOdinId: String,
    currentOdinId: String,
    resolvedDisplayName: String?,
    youLabel: String,
): String = when {
    currentOdinId.isNotEmpty() && authorOdinId == currentOdinId -> youLabel
    resolvedDisplayName != null -> resolvedDisplayName
    else -> authorOdinId
}

/**
 * Determines the content text to show in a reply quote.
 *
 * When a thumbnail is visible the image/video speaks for itself — the content
 * label ("Image", "Video") is suppressed to save horizontal space. Labels are
 * shown only for non-visual media (audio, file) or when no thumbnail rendered.
 *
 * @param replyText Text stored in the [ReplyPreview] (may be empty for media-only).
 * @param contentLabelText Content-type label text from [messageContentLabel] (e.g. "Image").
 * @param hasThumbnail Whether a thumbnail (encrypted or embedded) will be rendered.
 * @param hasMedia Whether the reply references any media at all.
 * @param mediaFallbackLabel Localized "Media" string used as last-resort fallback.
 */
fun resolveReplyContentText(
    replyText: String,
    contentLabelText: String?,
    hasThumbnail: Boolean,
    hasMedia: Boolean,
    mediaFallbackLabel: String,
): String {
    val effectiveLabelText = if (hasThumbnail) null else contentLabelText
    // trim(): a message body led by a newline lays out a blank first line, which
    // maxLines=1 + TextOverflow.Ellipsis paints as a bare "…". The body renders
    // clean via the markdown parser; the plain-text quote must trim to match.
    return effectiveLabelText
        ?: replyText.trim().ifEmpty { if (hasMedia) mediaFallbackLabel else "" }
}

/**
 * Whether the content-type icon should be shown alongside the content text.
 *
 * Suppressed when a thumbnail is visible (same rule as the label text).
 */
fun shouldShowContentIcon(hasThumbnail: Boolean, contentLabelText: String?): Boolean =
    !hasThumbnail && contentLabelText != null
