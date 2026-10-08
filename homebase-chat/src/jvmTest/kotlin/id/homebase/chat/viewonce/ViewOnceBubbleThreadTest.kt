package id.homebase.chat.viewonce

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import com.russhwolf.settings.PreferencesSettings
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.widget.MessageItem
import id.homebase.chat.widget.SwipeDistance
import id.homebase.chat.widget.SwipeRevealBox
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.prefs.Preferences
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The recipient's bubble in the thread reacts and replies like any message, and neither is a view. */
@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class)
class ViewOnceBubbleThreadTest {

    private val me = OdinId("me.example.com")
    private val alice = OdinId("alice.example.com")
    private val thumbsUp = "👍"

    private val koin = koinApplication {
        modules(
            module {
                single { UserPreferences(PreferencesSettings(Preferences.userRoot().node("/id/homebase/test/viewonce"))) }
                single { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
            },
        )
    }

    private val message = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = "",
        userDate = Clock.System.now(),
        modified = null,
        created = Clock.System.now(),
        originalAuthor = alice,
        sender = alice,
        displayName = "Alice",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = persistentListOf(
            PayloadDescriptor(key = VIEW_ONCE_PAYLOAD_KEY, contentType = "image/jpeg", iv = Base64.encode(ByteArray(16))),
        ),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
        messageContent = MessageContent.ViewOnce(ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE)),
    )

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        KoinIsolatedContext(koin) { HomebaseTheme(darkTheme = false) { content() } }
    }

    private fun ComposeUiTest.renderInThread(actions: MutableList<ConversationListUiAction>) = setContent {
        Host {
            SharedTransitionLayout {
                AnimatedVisibility(visible = true) {
                    MessageItem(
                        message = message,
                        userDefaultReactions = persistentListOf(thumbsUp),
                        decryptedFiles = persistentMapOf(),
                        currentOdinId = me.domainName,
                        animatedVisibilityScope = this@AnimatedVisibility,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        onUiAction = { actions += it },
                        downloadingFiles = emptySet(),
                    )
                }
            }
        }
    }

    private fun List<ConversationListUiAction>.views() = filter {
        it is ConversationListUiAction.MediaClicked || it is ConversationListUiAction.ViewOnceViewerClosed
    }

    @Test
    fun longPressOffersTheQuickReactionsAndReply_andReactingIsNotAView() = runComposeUiTest {
        val actions = mutableListOf<ConversationListUiAction>()
        renderInThread(actions)

        onNodeWithText("Photo").performTouchInput { longClick() }
        waitForIdle()
        onNodeWithText("Reply").assertExists()
        onAllNodesWithText(thumbsUp).onFirst().performClick()
        waitForIdle()

        val reaction = actions.filterIsInstance<ConversationListUiAction.ToggleReaction>().single()
        assertEquals(message.id, reaction.messageId)
        assertEquals(thumbsUp, reaction.reaction)
        assertTrue(actions.views().isEmpty(), "reacting never opens or consumes the media: $actions")
    }

    @Test
    fun longPressReplyQuotesTheMessage_andReplyingIsNotAView() = runComposeUiTest {
        val actions = mutableListOf<ConversationListUiAction>()
        renderInThread(actions)

        onNodeWithText("Photo").performTouchInput { longClick() }
        waitForIdle()
        onNodeWithText("Reply").performClick()
        waitForIdle()

        assertEquals(message.id, actions.filterIsInstance<ConversationListUiAction.ReplyToMessage>().single().message.id)
        assertTrue(actions.views().isEmpty(), "replying never opens or consumes the media: $actions")
    }

    @Test
    fun aSwipeOnTheOpenablePillRepliesAndNeverOpensIt() = runComposeUiTest {
        var replied = 0
        var opened = 0
        setContent {
            Host {
                // The wrapper only enables itself on a phone; this is the box it puts around every row.
                SwipeRevealBox(
                    onSwipeRight = { replied++ },
                    onSwipeLeft = null,
                    commitThreshold = SwipeDistance.Fixed(56.dp),
                    maxOffset = SwipeDistance.Fixed(96.dp),
                    enabled = true,
                    reveal = {},
                ) {
                    ViewOnceBubble(
                        descriptor = ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE),
                        isOutgoing = false,
                        shape = RoundedCornerShape(12),
                        containerColor = Color.LightGray,
                        contentColor = Color.Black,
                        modifier = Modifier.testTag(PILL),
                        canView = true,
                        onOpen = { opened++ },
                        onLongClick = {},
                        footer = { Text("10:42") },
                    )
                }
            }
        }

        onNodeWithTag(PILL).performTouchInput { down(centerLeft + Offset(4f, 0f)) }
        onNodeWithTag(PILL).performTouchInput { moveBy(Offset(40f, 0f)) }
        repeat(4) { onNodeWithTag(PILL).performTouchInput { moveBy(Offset(80f, 0f)) } }
        onNodeWithTag(PILL).performTouchInput { up() }
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()

        assertEquals(1, replied)
        assertEquals(0, opened)
    }

    private companion object {
        const val PILL = "pill"
    }
}
