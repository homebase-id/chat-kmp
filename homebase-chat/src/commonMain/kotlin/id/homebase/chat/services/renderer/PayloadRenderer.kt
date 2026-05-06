package id.homebase.chat.services.renderer

import androidx.compose.runtime.Immutable
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.client.location.LocationPreview
import id.homebase.chat.services.ChatProtocol

/**
 * A piece of composer content that knows how to render itself end-to-end:
 * preview row in the composer, payload contribution at send time, and (eventually)
 * its own bubble on the receiver. Each kind has a stable [id] used by the cancel-X
 * button and by the URL-detector to deduplicate (the detector replaces an existing
 * same-id link preview when the URL changes). Kinds whose presence implies a
 * specific message-header `dataType` (server-queryable kind axis) override
 * [implicitDataType].
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

    /**
     * Header-level `dataType` this renderer implies when included in a send.
     * Default 0 means "doesn't change the kind" — link previews, plain media
     * attachments, etc. Override on kinds that should be queryable as their
     * own message kind (Location today; future typed media if/when we add it).
     */
    val implicitDataType: Int get() = 0
}

@Immutable
data class LinkPreviewRenderer(val preview: LinkPreview) : PayloadRenderer {
    override val id: String get() = "link:${preview.url}"
}

@Immutable
data class LocationPreviewRenderer(val preview: LocationPreview) : PayloadRenderer {
    override val id: String get() = "loc:${preview.lat},${preview.lon}"
    override val implicitDataType: Int get() = ChatProtocol.ChatLocationMessageDataType
}

/**
 * Picks the message-header `dataType` for a list of staged renderers. Returns
 * the first non-zero `implicitDataType` it finds, or 0 if every renderer is
 * neutral. Mixing renderers of different implicit kinds isn't a thing today
 * (you can't stage a Location and a typed-media in the same composer); if it
 * ever becomes one, this is the place to decide precedence.
 */
fun List<PayloadRenderer>.toMessageDataType(): Int =
    firstOrNull { it.implicitDataType != 0 }?.implicitDataType ?: 0
