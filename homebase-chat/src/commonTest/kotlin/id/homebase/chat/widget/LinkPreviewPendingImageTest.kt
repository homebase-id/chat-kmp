package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.services.builder.LinkPreviewDescriptor
import kotlin.test.Test
import kotlin.uuid.Uuid

/**
 * Locks the pending/upload behaviour of the receiver-side [LinkPreviewCard].
 *
 * While a link-preview message is still uploading, its drive payload does not exist yet, so the
 * normal [id.homebase.core.image.HomebaseImage] fetch would fail and overlay a broken-image
 * triangle. The card must instead render a local source via Coil [coil3.compose.AsyncImage] — the
 * crisp `localImagePath` when present, else the embedded tinyThumb bytes. This case passes no
 * `localImagePath`, so it exercises the tinyThumb fallback.
 *
 * The test environment intentionally has NO Koin container. [HomebaseImage] resolves an
 * `ImageLoader` via `koinInject()` during composition, so any path that reaches it throws; AsyncImage
 * instead uses Coil's singleton loader and needs no Koin. The `isUploading = true` case below renders
 * to completion (title + description visible), which proves it took the local-source branch and never
 * touched HomebaseImage.
 */
@OptIn(ExperimentalTestApi::class)
class LinkPreviewPendingImageTest {

    // 1x1 transparent PNG — decodes under Skiko/Android image decoders. Used as the embedded
    // tinyThumb so the pending branch produces a non-null bitmap.
    private val onePxPngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    private val keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(32)))

    private val descriptor = LinkPreviewDescriptor(
        url = "https://example.com/article",
        hasImage = true,
        imageWidth = 1,
        imageHeight = 1,
        title = "Pending Title",
        description = "Pending Description",
    )

    @Test
    fun whileUploading_rendersEmbeddedThumbnail_withoutHittingHomebaseImage() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LinkPreviewCard(
                    descriptor = descriptor,
                    fileId = Uuid.random(),
                    driveId = Uuid.random(),
                    payloadKey = "chat_links",
                    keyHeader = keyHeader,
                    previewThumbnail = EmbeddedThumb(
                        pixelWidth = 1,
                        pixelHeight = 1,
                        contentType = "image/png",
                        content = onePxPngBase64,
                    ),
                    isUploading = true,
                )
            }
        }
        // Composition completed (no Koin/ImageLoader required) and the card body rendered,
        // confirming the embedded-thumbnail branch was taken instead of HomebaseImage.
        onNodeWithText("Pending Title").assertExists()
        onNodeWithText("Pending Description").assertExists()
    }
}
