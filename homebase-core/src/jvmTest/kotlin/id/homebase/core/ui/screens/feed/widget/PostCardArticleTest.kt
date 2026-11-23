package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.services.LocalAttachmentContextStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class PostCardArticleTest {

    private val koin = koinApplication {
        modules(
            module {
                single { AvatarResolver(MutableStateFlow(emptyList())) }
                single { LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob())) }
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

    private val headerKey = FeedProtocol.mediaPayloadKey(0)

    private fun post(
        type: PostType = PostType.Article,
        caption: String = "The title",
        articleHeaderKey: String? = null,
        payloadKeys: List<String> = emptyList(),
    ) = FeedPostItem(
        id = Uuid.random(),
        fileId = Uuid.random(),
        globalTransitId = null,
        driveId = Uuid.random(),
        keyHeader = KeyHeader.empty(),
        payloads = payloadKeys.map { PayloadDescriptor(key = it) },
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
        articleHeaderKey = articleHeaderKey,
    )

    @Test
    fun headerPayload_isTheKeyedPayload() {
        val p = post(articleHeaderKey = headerKey, payloadKeys = listOf(FeedProtocol.mediaPayloadKey(1), headerKey))
        assertEquals(headerKey, p.articleHeaderPayload()?.key)
    }

    @Test
    fun headerPayload_nullWithoutKeyOrMatch() {
        assertNull(post(payloadKeys = listOf(headerKey)).articleHeaderPayload())
        assertNull(post(articleHeaderKey = headerKey, payloadKeys = listOf("other")).articleHeaderPayload())
    }

    private fun render(post: FeedPostItem, onPostClick: () -> Unit = {}, check: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
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
    fun articleWithoutHeader_showsTitleAndOpensOnTap() {
        var clicks = 0
        render(post(), onPostClick = { clicks++ }) {
            onNodeWithText("The title").assertIsDisplayed().performClick()
            assertEquals(1, clicks)
        }
    }

    @Test
    fun articleWithHeader_hidesTitleText() {
        render(post(articleHeaderKey = headerKey, payloadKeys = listOf(headerKey))) {
            onNodeWithText("The title").assertDoesNotExist()
        }
    }

    @Test
    fun otherPostTypes_stillShowCaption() {
        render(post(type = PostType.Tweet, caption = "Plain caption")) {
            onNodeWithText("Plain caption").assertIsDisplayed()
        }
    }
}
