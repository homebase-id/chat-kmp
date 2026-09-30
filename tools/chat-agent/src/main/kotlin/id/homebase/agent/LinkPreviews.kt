package id.homebase.agent

import id.homebase.api.client.link.LinkPreview
import id.homebase.api.client.link.LinkPreviewProvider

private val URL_REGEX = Regex("https?://[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)
private const val URL_TRAILING = ".,;:!?)]}>*_"

fun firstUrl(text: String): String? =
    URL_REGEX.find(text)?.value?.trimEnd { it in URL_TRAILING }?.takeIf { it.length > 8 }

fun interface LinkPreviewSource {
    suspend fun preview(url: String): LinkPreview?
}

// The identity server fetches the page (same /links/extract call as the app); this machine never requests chat-supplied URLs.
fun serverLinkPreviews(session: Session) = LinkPreviewSource { url ->
    LinkPreviewProvider(session.http, session.credentials).getLinkPreview(url)
}
