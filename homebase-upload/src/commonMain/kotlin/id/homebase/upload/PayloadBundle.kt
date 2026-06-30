package id.homebase.upload

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb

// Aggregated upload payload set, consumed by the shared upload pipeline and every
// feature sender (Chat, Moments, Vault, …).
data class PayloadBundle(
    val payloads: List<PayloadFile>,
    val thumbnails: List<ThumbnailFile>,
    val previewThumbs: List<EmbeddedThumb>
)

/**
 * The native server-side thumbnail descriptors for [payloadKey], to attach to an
 * optimistic [ThumbnailDescriptor] list on a local PayloadDescriptor. `content`
 * is null — the bytes live in the seeded disk cache (see PayloadCacheSeeder), not
 * inline on the descriptor; this list only carries the native sizes so the display
 * can request a native thumbnail size and hit the seed. Null when there are none.
 *
 * Shared by every optimistic-write sender (Chat, Moments, Vault) so the descriptor
 * shape stays identical across them.
 */
fun PayloadBundle.thumbnailDescriptorsFor(payloadKey: String): List<ThumbnailDescriptor>? =
    thumbnails
        .filter { it.key == payloadKey }
        .map {
            ThumbnailDescriptor(
                pixelWidth = it.pixelWidth,
                pixelHeight = it.pixelHeight,
                contentType = it.contentType,
                content = null,
            )
        }
        .ifEmpty { null }
