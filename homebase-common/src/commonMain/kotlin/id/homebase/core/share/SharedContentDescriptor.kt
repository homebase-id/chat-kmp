package id.homebase.core.share

import kotlinx.serialization.Serializable

/**
 * Describes content shared from an external app into Homebase Chat.
 * On iOS, the share extension writes this to the App Group container;
 * the main app reads it to complete sending.
 * On Android, it's used in-process as a data model.
 */
@Serializable
data class SharedContentDescriptor(
    val contentType: SharedContentType,
    val text: String? = null,
    val url: String? = null,
    val fileNames: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val targetConversationId: String,
)

/**
 * Body to send for a text/URL share — the iOS counterpart of Android's whole
 * `EXTRA_TEXT`, which already carries caption and link together.
 *
 * Two ways a link gets lost if [text] and [url] are treated as either/or (#1097):
 * a blank [text] shadows the [url] (iOS vends an empty `public.plain-text` next to
 * the real link for some hosts, e.g. Google Maps) and sends an empty message; a
 * non-blank [text] shadows it and sends the caption without the link. So blank is
 * treated as absent, and a caption is carried *alongside* its link rather than
 * instead of it — unless the caption already contains the link, which is the common
 * case and must not be duplicated.
 */
fun SharedContentDescriptor.resolveMessageBody(): String {
    val sharedText = text?.takeIf { it.isNotBlank() }
    val sharedUrl = url?.takeIf { it.isNotBlank() }
    return when {
        sharedText == null -> sharedUrl.orEmpty()
        sharedUrl == null -> sharedText
        sharedText.contains(sharedUrl) -> sharedText
        else -> "${sharedText.trimEnd()}\n$sharedUrl"
    }
}

@Serializable
enum class SharedContentType {
    TEXT,
    URL,
    IMAGE,
    VIDEO,
    FILE,
    MIXED,
}
