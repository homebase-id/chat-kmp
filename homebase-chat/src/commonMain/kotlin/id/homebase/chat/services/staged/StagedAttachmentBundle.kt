package id.homebase.chat.services.staged

import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.builder.LinkPreviewPayloadBuilder
import id.homebase.chat.services.builder.LocationPreviewPayloadBuilder

/**
 * Convert a list of staged attachments to a single combined [PayloadBundle] suitable for the
 * chat send pipeline. Returns `null` when the list is empty (the send pipeline treats `null`
 * as "no payloads").
 *
 * This is the single dispatch point between staged-content kinds and their on-wire builder.
 * `ConversationListViewModel.addMessage` and `replyToMessage` call this once instead of
 * managing a manual `link/location/contact?` combination — adding a new kind is a one-line
 * `when` branch addition here, not a new conditional in every send path.
 */
suspend fun List<StagedAttachment>.toCombinedPayloadBundle(
    fileOps: FileOperationsProvider,
): PayloadBundle? {
    if (isEmpty()) return null
    val bundles = map { it.toPayloadBundle(fileOps) }
    return when (bundles.size) {
        1 -> bundles.single()
        else -> PayloadBundle(
            payloads = bundles.flatMap { it.payloads },
            thumbnails = bundles.flatMap { it.thumbnails },
            previewThumbs = bundles.flatMap { it.previewThumbs },
        )
    }
}

private suspend fun StagedAttachment.toPayloadBundle(
    fileOps: FileOperationsProvider,
): PayloadBundle = when (this) {
    is StagedLinkPreview -> LinkPreviewPayloadBuilder.build(preview, fileOps)
    is StagedLocationPreview -> LocationPreviewPayloadBuilder.build(preview, fileOps)
}
