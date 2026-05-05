package id.homebase.chat.services.staged

import androidx.compose.runtime.Immutable
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.client.location.LocationPreview

/**
 * A piece of content the user has staged in the chat composer to be sent with the next message.
 *
 * Each kind has a stable [id] used by the cancel-X button and by the URL-detector logic to
 * deduplicate (the detector replaces an existing same-id link preview when the URL changes).
 *
 * Adding a new attachment kind:
 *   1. add a `data class StagedXxx(val ...) : StagedAttachment` here,
 *   2. add a `when` branch in `StagedAttachmentRow` (UI dispatch),
 *   3. add a `when` branch in `List<StagedAttachment>.toCombinedPayloadBundle` (wire-format dispatch).
 *
 * No new params on [MessageInputBar] / [MessageTextFieldExpanded] / [MessageTextFieldCompact],
 * no new branches in `ConversationListViewModel`. Compare to the pre-staged-attachments shape
 * where each new preview kind grew the parameter list of three composables and added a manual
 * combine-bundles helper in the ViewModel.
 */
@Immutable
sealed interface StagedAttachment {
    val id: String
}

@Immutable
data class StagedLinkPreview(val preview: LinkPreview) : StagedAttachment {
    override val id: String get() = "link:${preview.url}"
}

@Immutable
data class StagedLocationPreview(val preview: LocationPreview) : StagedAttachment {
    override val id: String get() = "loc:${preview.lat},${preview.lon}"
}
