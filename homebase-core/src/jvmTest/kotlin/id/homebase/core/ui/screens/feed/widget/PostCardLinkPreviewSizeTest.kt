package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.builder.LinkPreviewDescriptor
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class PostCardLinkPreviewSizeTest {

    private val onePxPngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    private val title = "Linked page"

    private val koin = koinApplication {
        modules(
            module {
                single {
                    ImageLoader.Builder(PlatformContext.INSTANCE)
                        .components {
                            add(Interceptor { chain -> ErrorResult(null, chain.request, UnsupportedOperationException()) })
                        }
                        .build()
                }
            },
        )
    }

    private fun post(thumbWidth: Int, thumbHeight: Int) = FeedPostItem(
        id = Uuid.random(),
        fileId = Uuid.random(),
        globalTransitId = null,
        driveId = Uuid.random(),
        keyHeader = KeyHeader.empty(),
        payloads = listOf(
            PayloadDescriptor(
                key = FeedProtocol.LinksPayloadKey,
                descriptorContent = OdinSystemSerializer.serialize(
                    listOf(
                        LinkPreviewDescriptor(
                            url = "https://example.com",
                            hasImage = true,
                            // Declared og:image size disagrees with the real file; it must be ignored.
                            imageWidth = 600,
                            imageHeight = 600,
                            description = "",
                            title = title,
                        )
                    )
                ),
                previewThumbnail = ThumbnailDescriptor(
                    pixelWidth = thumbWidth,
                    pixelHeight = thumbHeight,
                    contentType = "image/png",
                    content = onePxPngBase64,
                ),
            )
        ),
        caption = "",
        type = PostType.Tweet,
        channelId = Uuid.random().toString(),
        slug = "post",
        reactAccess = ReactAccess.All,
        embeddedPost = null,
        userDateMs = 0,
        createdMs = 0,
        previewThumbnail = null,
        reactionPreview = null,
        senderOdinId = null,
        originalAuthor = null,
        versionTag = null,
        ownReactions = emptyList(),
        commentCount = 0,
        isEncrypted = false,
        acl = null,
    )

    // A 1000-tall window caps feed media at 700; the card's 16dp insets leave the image 32dp narrower than the card.
    private fun assertImageSize(
        cardWidth: Dp,
        thumbWidth: Int,
        thumbHeight: Int,
        expectedHeight: Dp,
    ) = runDesktopComposeUiTest(width = 1200, height = 1000) {
        setContent {
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    PostCard(
                        post = post(thumbWidth, thumbHeight),
                        displayName = "",
                        channelName = null,
                        onPostClick = {},
                        onAuthorClick = {},
                        onMediaClick = {},
                        onToggleReaction = {},
                        onOpenComments = {},
                        onShowReactors = {},
                        modifier = Modifier.width(cardWidth),
                    )
                }
            }
        }
        onAllNodesWithContentDescription(title, useUnmergedTree = true).onFirst()
            .assertWidthIsEqualTo(cardWidth - 32.dp)
            .assertHeightIsEqualTo(expectedHeight)
    }

    @Test
    fun wideCard_followsImageAspectRatioPastChatCap() = assertImageSize(900.dp, 1200, 630, 456.dp)

    @Test
    fun squareImage_isCappedToFeedMediaHeight() = assertImageSize(900.dp, 600, 600, 700.dp)

    @Test
    fun tallImage_isFlooredToFeedMediaAspect() = assertImageSize(400.dp, 600, 900, 460.dp)
}
