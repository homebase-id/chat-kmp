package id.homebase.chat.services.renderer

import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.builder.LinkPreviewPayloadBuilder
import id.homebase.chat.services.builder.LocationPreviewPayloadBuilder

/**
 * Convert a list of attachment renderers to a single combined [PayloadBundle] suitable for the
 * chat send pipeline. Returns `null` when the list is empty (the send pipeline treats `null`
 * as "no payloads").
 *
 * Single dispatch point between renderer kinds and their on-wire builders. Adding a new kind
 * is a one-line `when` branch here, not a new conditional in every send path.
 */
suspend fun List<PayloadRenderer>.toCombinedPayloadBundle(
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

private suspend fun PayloadRenderer.toPayloadBundle(
    fileOps: FileOperationsProvider,
): PayloadBundle = when (this) {
    is LinkPreviewRenderer -> LinkPreviewPayloadBuilder.build(preview, fileOps)
    is LocationPreviewRenderer -> LocationPreviewPayloadBuilder.build(preview, fileOps)
}
