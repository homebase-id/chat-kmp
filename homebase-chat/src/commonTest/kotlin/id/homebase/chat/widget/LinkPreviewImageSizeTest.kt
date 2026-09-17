package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.services.builder.LinkPreviewDescriptor
import kotlin.test.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class LinkPreviewImageSizeTest {

    private val onePxPngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    private val title = "Image Title"

    private fun assertImageSize(
        cardWidth: Dp,
        thumbWidth: Int,
        thumbHeight: Int,
        expectedHeight: Dp,
        imageMaxHeight: Dp = 180.dp,
        imageMinAspectRatio: Float = 0f,
    ) =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    Box(Modifier.width(cardWidth)) {
                        LinkPreviewCard(
                            descriptor = LinkPreviewDescriptor(
                                url = "https://example.com",
                                hasImage = true,
                                // Declared og:image size disagrees with the real file; it must be ignored.
                                imageWidth = 600,
                                imageHeight = 600,
                                title = title,
                                description = "",
                            ),
                            fileId = Uuid.random(),
                            driveId = Uuid.random(),
                            payloadKey = "chat_links",
                            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(32))),
                            previewThumbnail = EmbeddedThumb(
                                pixelWidth = thumbWidth,
                                pixelHeight = thumbHeight,
                                contentType = "image/png",
                                content = onePxPngBase64,
                            ),
                            isUploading = true,
                            imageMaxHeight = imageMaxHeight,
                            imageMinAspectRatio = imageMinAspectRatio,
                        )
                    }
                }
            }
            onNodeWithContentDescription(title, useUnmergedTree = true)
                .assertWidthIsEqualTo(cardWidth)
                .assertHeightIsEqualTo(expectedHeight)
        }

    @Test
    fun wideImageOnNarrowCard_followsAspectRatio() = assertImageSize(300.dp, 20, 10, 150.dp)

    @Test
    fun wideImageOnWideCard_spansWidthAtCappedHeight() = assertImageSize(500.dp, 20, 10, 180.dp)

    @Test
    fun veryWideImage_clampsToFourToOne() = assertImageSize(300.dp, 100, 10, 75.dp)

    @Test
    fun raisedMaxHeight_letsWideCardFollowAspectRatio() =
        assertImageSize(700.dp, 20, 10, 350.dp, imageMaxHeight = 500.dp)

    @Test
    fun raisedMaxHeight_stillCapsSquareImage() =
        assertImageSize(700.dp, 10, 10, 500.dp, imageMaxHeight = 500.dp)

    @Test
    fun minAspectRatio_floorsTallImage() =
        assertImageSize(300.dp, 10, 20, 375.dp, imageMaxHeight = 1000.dp, imageMinAspectRatio = 0.8f)
}
