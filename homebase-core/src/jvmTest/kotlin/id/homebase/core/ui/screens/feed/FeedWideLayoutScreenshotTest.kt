package id.homebase.core.ui.screens.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.services.LocalAttachmentContextStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import id.homebase.api.client.contacts.AvatarResolver
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.Base64Bytes
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import kotlin.uuid.Uuid
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FeedWideLayoutScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private fun ComposeUiTest.save(name: String) {
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 5) for (y in 0 until image.height step 5) colors += image.getRGB(x, y)
        assertTrue(colors.size > 3, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @BeforeTest
    fun startDi() {
        startKoin {
            modules(
                module {
                    single { AvatarResolver(MutableStateFlow(emptyList())) }
                    single { LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob())) }
                    // Avatars fall back to initials instead of reaching the demo identities over the network.
                    single<ImageLoader> {
                        ImageLoader.Builder(PlatformContext.INSTANCE)
                            .components { add(Interceptor { ErrorResult(null, it.request, IllegalStateException("offline")) }) }
                            .build()
                    }
                },
            )
        }
    }

    @AfterTest
    fun stopDi() = stopKoin()

    private val people = listOf(
        SuggestedPerson("samwise.gamgee.demo.rocks", isFollowed = false),
        SuggestedPerson("frodo.baggins.demo.rocks", isFollowed = true),
        SuggestedPerson("merry.brandybuck.demo.rocks", isFollowed = false),
        SuggestedPerson("pippin.took.demo.rocks", isFollowed = false),
    )
    private val names = mapOf(
        "samwise.gamgee.demo.rocks" to "Samwise Gamgee",
        "frodo.baggins.demo.rocks" to "Frodo Baggins",
        "pippin.took.demo.rocks" to "Peregrin Took",
    )

    private fun gradientPng(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = GradientPaint(0f, 0f, Color(0x3E6B4A), w.toFloat(), h.toFloat(), Color(0xD9B26F))
        g.fillRect(0, 0, w, h)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun post(author: String, caption: String, withPhoto: Boolean, comments: Int, hearts: Int): FeedPostItem {
        val thumb = if (withPhoto) EmbeddedThumb(64, 40, "image/png", Base64Bytes(gradientPng(64, 40))) else null
        return FeedPostItem(
            id = Uuid.random(),
            fileId = Uuid.random(),
            globalTransitId = null,
            driveId = Uuid.random(),
            keyHeader = KeyHeader.empty(),
            payloads = if (withPhoto) listOf(PayloadDescriptor(key = "${FeedProtocol.MediaPayloadKeyPrefix}0", contentType = "image/png")) else emptyList(),
            caption = caption,
            type = PostType.Tweet,
            channelId = Uuid.random().toString(),
            slug = "post",
            reactAccess = ReactAccess.All,
            embeddedPost = null,
            userDateMs = 1_762_000_000_000,
            createdMs = 1_762_000_000_000,
            previewThumbnail = thumb,
            reactionPreview = ReactionSummary(
                reactions = if (hearts > 0) mapOf("h" to ReactionEntry("h", hearts, "\u2764\uFE0F")) else emptyMap(),
                totalCommentCount = comments,
            ),
            senderOdinId = null,
            originalAuthor = OdinId(author),
            versionTag = null,
            ownReactions = emptyList(),
            commentCount = comments,
            isEncrypted = false,
            acl = null,
        )
    }

    private val posts by lazy {
        listOf(
            post("samwise.gamgee.demo.rocks", "Potatoes are in. Boil 'em, mash 'em, stick 'em in a stew.", withPhoto = true, comments = 3, hearts = 5),
            post("frodo.baggins.demo.rocks", "Back from the long walk. The Shire looks smaller than I remember, and greener.", withPhoto = false, comments = 1, hearts = 2),
            post("pippin.took.demo.rocks", "Second breakfast at the Green Dragon, anyone?", withPhoto = true, comments = 0, hearts = 0),
        )
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    private fun PolishedFeed(wide: Boolean, people: List<SuggestedPerson>, channels: List<String>) {
        FeedColumns(
            wide = wide,
            modifier = if (wide) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier,
            suggestions = {
                FeedSuggestionsColumn(
                    people = people,
                    channelNames = channels,
                    displayNameFor = { names[it] ?: it },
                    onFollow = {},
                    onUnfollow = {},
                    onPersonClick = {},
                    onSeeAll = {},
                )
            },
        ) {
            SharedTransitionLayout {
                AnimatedVisibility(visible = true) {
                    FeedTimelineList(
                        uiState = FeedTimelineUiState(posts = posts, endReached = true),
                        wide = wide,
                        listState = rememberLazyListState(),
                        onRefresh = {},
                        onLoadMore = {},
                        onPostClick = {},
                        onOpenComments = {},
                        onOpenMedia = { _, _, _ -> },
                        onShowReactors = {},
                        onToggleReaction = { _, _ -> },
                        onAuthorClick = {},
                        onDeletePost = {},
                        onReportPost = {},
                        onBlockAuthor = {},
                        selfOdinId = null,
                        channels = emptyMap(),
                        channelNameFor = { "Public posts" },
                        isPublicChannel = { true },
                        displayNames = names.mapKeys { OdinId(it.key) },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private fun polishShot(name: String, width: Int, height: Int, wide: Boolean, dark: Boolean, empty: Boolean = false) =
        runDesktopComposeUiTest(width, height) {
            setContent {
                HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                    Box(Modifier.width(width.dp).height(height.dp)) {
                        PolishedFeed(
                            wide = wide,
                            people = if (empty) emptyList() else people,
                            channels = if (empty) emptyList() else listOf("Hiking", "Public posts", "The Shire"),
                        )
                    }
                }
            }
            waitForIdle()
            onAllNodesWithTag(FEED_SUGGESTIONS_TAG).assertCountEquals(if (wide) 1 else 0)
            save(name)
        }

    @Test fun polishWideLight() = polishShot("feed-wide-light", 1400, 900, wide = true, dark = false)
    @Test fun polishWideDark() = polishShot("feed-wide-dark", 1400, 900, wide = true, dark = true)
    @Test fun polishWideEmptyLight() = polishShot("feed-wide-empty-light", 1000, 700, wide = true, dark = false, empty = true)
    @Test fun polishPhoneLight() = polishShot("feed-phone-light", 412, 900, wide = false, dark = false)
    @Test fun polishPhoneDark() = polishShot("feed-phone-dark", 412, 900, wide = false, dark = true)
    @Test fun polishNarrowLight() = polishShot("feed-narrow-700-light", 700, 900, wide = false, dark = false)

    @Composable
    private fun Harness(wide: Boolean, listState: androidx.compose.foundation.lazy.LazyListState) {
        FeedColumns(
            wide = wide,
            suggestions = { Text("suggestions", Modifier.testTag("suggestionsBody")) },
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("list")) {
                items((0 until 60).toList()) { Text("post $it", Modifier.height(80.dp)) }
            }
        }
    }

    @Test
    fun wideShowsTimelineAtWebWidthBesideSuggestions() = runDesktopComposeUiTest(1400, 900) {
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                Box(Modifier.width(1400.dp).height(900.dp)) {
                    Harness(wide = true, listState = rememberLazyListState())
                }
            }
        }
        val centre = onNodeWithTag(FEED_CENTRE_TAG).fetchSemanticsNode().boundsInRoot
        val side = onNodeWithTag(FEED_SUGGESTIONS_TAG).fetchSemanticsNode().boundsInRoot
        val density = 1f
        assertEquals(FeedContentMaxWidth.value * density, centre.width, 1f)
        assertEquals(FeedSuggestionsWidth.value * density, side.width, 1f)
        assertTrue(side.left >= centre.right, "suggestions sit right of the timeline")
    }

    @Test
    fun narrowKeepsSingleColumn() = runDesktopComposeUiTest(700, 900) {
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                Box(Modifier.width(700.dp).height(900.dp)) {
                    Harness(wide = false, listState = rememberLazyListState())
                }
            }
        }
        onAllNodesWithTag(FEED_SUGGESTIONS_TAG).assertCountEquals(0)
        onAllNodesWithTag("list").assertCountEquals(1)
    }

    @Test
    fun resizingAcrossTheGateKeepsScrollPosition() = runDesktopComposeUiTest(1400, 900) {
        var wide by mutableStateOf(true)
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                state = rememberLazyListState()
                Box(Modifier.width(1400.dp).height(900.dp)) { Harness(wide = wide, listState = state) }
            }
        }
        runOnIdle { kotlinx.coroutines.runBlocking { state.scrollToItem(25, 10) } }
        waitForIdle()
        wide = false
        waitForIdle()
        assertEquals(25, state.firstVisibleItemIndex)
        assertEquals(10, state.firstVisibleItemScrollOffset)
        wide = true
        waitForIdle()
        assertEquals(25, state.firstVisibleItemIndex)
        assertEquals(10, state.firstVisibleItemScrollOffset)
        onAllNodesWithTag(FEED_SUGGESTIONS_TAG).assertCountEquals(1)
    }

    @Test
    fun unfollowingASuggestionFlipsItsToggleBack() {
        val followedHere = id.homebase.core.ui.screens.feed.following.FollowingUiState(
            connections = listOf("sam.demo.rocks"),
            following = listOf("sam.demo.rocks"),
            followedHere = setOf("sam.demo.rocks"),
        )
        assertEquals(listOf(SuggestedPerson("sam.demo.rocks", isFollowed = true)), followedHere.suggestedPeople())
        val unfollowed = followedHere.copy(following = emptyList())
        assertEquals(listOf(SuggestedPerson("sam.demo.rocks", isFollowed = false)), unfollowed.suggestedPeople())
    }
}
