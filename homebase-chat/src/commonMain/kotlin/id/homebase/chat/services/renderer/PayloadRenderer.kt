package id.homebase.chat.services.renderer

import androidx.compose.runtime.Immutable
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.client.location.LocationPreview

/**
 * A piece of composer content that knows how to render itself end-to-end:
 * preview row in the composer, payload contribution at send time, and (eventually)
 * its own bubble on the receiver. Each kind has a stable [id] used by the cancel-X
 * button and by the URL-detector to deduplicate (the detector replaces an existing
 * same-id link preview when the URL changes).
 *
 * Adding a new kind:
 *   1. add a `data class XxxRenderer(val ...) : PayloadRenderer` here,
 *   2. add a `when` branch in `PayloadRendererRow` (UI dispatch),
 *   3. add a `when` branch in `List<PayloadRenderer>.toCombinedPayloadBundle` (wire-format dispatch).
 *
 * No new params on [MessageInputBar] / [MessageTextFieldExpanded] / [MessageTextFieldCompact],
 * no new branches in `ConversationListViewModel`.
 */
@Immutable
sealed interface PayloadRenderer {
    val id: String
}

@Immutable
data class LinkPreviewRenderer(val preview: LinkPreview) : PayloadRenderer {
    override val id: String get() = "link:${preview.url}"
}

@Immutable
data class LocationPreviewRenderer(val preview: LocationPreview) : PayloadRenderer {
    override val id: String get() = "loc:${preview.lat},${preview.lon}"
}
