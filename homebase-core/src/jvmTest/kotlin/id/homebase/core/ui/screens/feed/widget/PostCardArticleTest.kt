package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.contacts.AvatarResolver
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.Base64Bytes
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// The image fetch fails in tests; HomebaseImage then draws this preview, which carries the content description.
private const val OnePixelPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="

@OptIn(ExperimentalTestApi::class, ExperimentalEncodingApi::class)
class PostCardArticleTest {

    private val scope = CoroutineScope(SupervisorJob())

    private val koin = koinApplication {
        modules(
            module {
                single { AvatarResolver(MutableStateFlow(emptyList())) }
                single { LocalAttachmentContextStore(EventBus(), scope) }
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

    @AfterTest
    fun tearDown() {
        koin.close()
        scope.cancel()
    }

    private fun post(
        type: PostType = PostType.Article,
        caption: String = "The title",
        hasHeader: Boolean = false,
    ) = FeedPostItem(
        id = Uuid.random(),
        fileId = Uuid.random(),
        globalTransitId = null,
        driveId = Uuid.random(),
        keyHeader = KeyHeader.empty(),
        payloads = emptyList(),
        caption = caption,
        type = type,
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
        articleHeader = if (hasHeader) {
            PayloadDescriptor(
                key = FeedProtocol.mediaPayloadKey(0),
                contentType = "image/png",
                previewThumbnail = ThumbnailDescriptor(1, 1, "image/png", Base64Bytes(Base64.decode(OnePixelPng))),
            )
        } else {
            null
        },
        articleSummary = "The abstract",
    )

    private fun render(post: FeedPostItem, onPostClick: () -> Unit = {}, check: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 1200, height = 1000) {
            setContent {
                KoinIsolatedContext(koin) {
                    MaterialTheme {
                        PostCard(
                            post = post,
                            displayName = "",
                            channelName = null,
                            onPostClick = onPostClick,
                            onAuthorClick = {},
                            onMediaClick = {},
                            onToggleReaction = {},
                            onOpenComments = {},
                            onShowReactors = {},
                            modifier = Modifier.width(600.dp),
                        )
                    }
                }
            }
            check()
        }

    @Test
    fun articleWithoutHeader_showsTitleAndSummaryAndOpensOnTap() {
        var clicks = 0
        render(post(), onPostClick = { clicks++ }) {
            onNodeWithText("The abstract").assertIsDisplayed()
            onNodeWithText("The title").assertIsDisplayed().performClick()
            assertEquals(1, clicks)
        }
    }

    @Test
    fun articleWithHeader_stillShowsTitleAndSummary() {
        render(post(hasHeader = true)) {
            onNodeWithText("The title").assertIsDisplayed()
            onNodeWithText("The abstract").assertIsDisplayed()
        }
    }

    @Test
    fun articleHeader_isCroppedToTwoByOne() {
        render(post(hasHeader = true)) {
            val bounds = onNodeWithContentDescription("Image attachment", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertEquals(2f, bounds.width / bounds.height, 0.01f)
        }
    }

    @Test
    fun articleCard_announcesItselfAsAnArticle() {
        render(post()) {
            val card = onNodeWithText("The title").fetchSemanticsNode().config
            assertEquals("Open article", card[SemanticsActions.OnClick].label)
            assertEquals(Role.Button, card[SemanticsProperties.Role])
        }
    }

    @Test
    fun otherPostTypes_stillShowCaption() {
        render(post(type = PostType.Tweet, caption = "Plain caption")) {
            onNodeWithText("Plain caption").assertIsDisplayed()
        }
    }
}
