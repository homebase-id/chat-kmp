package id.homebase.chat.contactcard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.russhwolf.settings.PreferencesSettings
import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.widget.MessageBubbleRaw
import id.homebase.chat.widget.MessageItem
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What the contact card offers, and to whom. Both halves are semantics-level, because both defects
 * they guard are invisible in a screenshot:
 *
 *  - off-stream (action-menu preview, message info, reply quote) the card must take NO pointer —
 *    a handler that can't open the detail still eats the tap the action menu's scrim needs, and
 *    the detail would draw over the surface that drew the preview;
 *  - the Save affordance follows the callback, on the sent side as well as the received one. A
 *    card you forwarded to yourself opens out of your own conversation and must still be savable.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class)
class ContactCardBubbleInteractionTest {

    private val me = OdinId("me.example.com")

    // A rendered bubble reaches rememberHaptics, which resolves UserPreferences out of Koin.
    private val preferences =
        UserPreferences(PreferencesSettings(Preferences.userRoot().node("/id/homebase/test")))

    // Isolated, not KoinApplication: that one starts the process-wide Koin and never stops it, so
    // the second suite in the JVM to compose one silently inherits the first suite's modules.
    private val koin = koinApplication { modules(module { single { preferences } }) }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        KoinIsolatedContext(koin) {
            HomebaseTheme(darkTheme = false) { content() }
        }
    }

    private val card = ContactCardDescriptor(
        displayName = "Ada Vance",
        organization = "Homebase",
        phones = listOf("+14155550123"),
        emails = listOf("ada@example.com"),
    )

    private val identity = "ada.example.com"
    private val identityCard = card.copy(odinId = identity)

    // Rendered strings, so a renamed resource fails here rather than passing vacuously.
    private val saveLabel = "Save to contacts"
    private val messageLabel = "Message"
    private val openProfileLabel = "Open profile"

    private fun message(
        author: OdinId? = null,
        content: ContactCardDescriptor = card,
    ) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = "",
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = author,
        sender = author,
        displayName = "Ada Vance",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
        messageContent = MessageContent.ContactCard(content),
    )

    @Test
    fun `an off-stream card takes no click action`() = runComposeUiTest {
        setContent {
            HomebaseTheme(darkTheme = false) {
                ContactCardBubble(descriptor = card, canOpenDetail = false, onLongClick = {})
            }
        }

        onNodeWithText("Ada Vance").assertHasNoClickAction()
    }

    @Test
    fun `an in-stream card carries the open-detail click`() = runComposeUiTest {
        setContent {
            HomebaseTheme(darkTheme = false) {
                ContactCardBubble(descriptor = card, canOpenDetail = true, onLongClick = {})
            }
        }

        onNodeWithText("Ada Vance").assertHasClickAction()
    }

    @Test
    fun `Save is offered only when the host can act on it, and hands back the card`() =
        runComposeUiTest {
            var saved: ContactCardDescriptor? = null
            setContent {
                HomebaseTheme(darkTheme = false) {
                    ContactCardBubble(descriptor = card, onSaveToContacts = { saved = it })
                }
            }

            onNodeWithText(saveLabel).assertIsDisplayed()
            onNodeWithText(saveLabel).performClick()

            assertEquals(card, saved)
        }

    @Test
    fun `a card with no save handler shows no Save affordance`() = runComposeUiTest {
        setContent {
            HomebaseTheme(darkTheme = false) {
                ContactCardBubble(descriptor = card, onSaveToContacts = null)
            }
        }

        onNodeWithText(saveLabel).assertDoesNotExist()
    }

    // Driven through MessageItem, not MessageBubbleRaw: the sent-side defect was the callback
    // never reaching SentMessageBubble, which a direct MessageBubbleRaw call cannot see.
    @Test
    fun `a sent card in the stream routes Save out to the host`() = runComposeUiTest {
        val actions = mutableListOf<ConversationListUiAction>()
        setContent {
            Host {
                SharedTransitionLayout {
                    AnimatedVisibility(visible = true) {
                        MessageItem(
                            message = message(author = me),
                            userDefaultReactions = persistentListOf(),
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

        onNodeWithText("Ada Vance").assertHasClickAction()
        onNodeWithText(saveLabel).performClick()

        assertEquals(ConversationListUiAction.SaveContactCard(card), actions.singleOrNull())
    }

    @Test
    fun `the identity row leads with Message and hands back the identity`() = runComposeUiTest {
        var messaged: String? = null
        setContent {
            HomebaseTheme(darkTheme = false) {
                ContactCardBubble(
                    descriptor = identityCard,
                    onMessageIdentity = { messaged = it },
                )
            }
        }

        onNodeWithText("Ada Vance").performClick()
        onNodeWithText(messageLabel).performClick()

        assertEquals(identity, messaged)
    }

    // Open profile is the row's only action when nothing can route a chat, so a handler that
    // stops arriving must not leave the identity inert.
    @Test
    fun `an identity row with no message handler keeps Open profile`() = runComposeUiTest {
        setContent {
            HomebaseTheme(darkTheme = false) {
                ContactCardBubble(descriptor = identityCard, onMessageIdentity = null)
            }
        }

        onNodeWithText("Ada Vance").performClick()

        onNodeWithText(openProfileLabel).assertIsDisplayed()
        onNodeWithText(messageLabel).assertDoesNotExist()
    }

    // Driven through MessageItem, not the bubble: the callback crosses four layers of threading
    // to reach the card, and a direct ContactCardBubble call cannot see a site that drops it.
    @Test
    fun `a received card in the stream routes Message out to the host`() = runComposeUiTest {
        val actions = mutableListOf<ConversationListUiAction>()
        setContent {
            Host {
                SharedTransitionLayout {
                    AnimatedVisibility(visible = true) {
                        MessageItem(
                            message = message(content = identityCard),
                            userDefaultReactions = persistentListOf(),
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

        onNodeWithText("Ada Vance").performClick()
        onNodeWithText(messageLabel).performClick()

        assertEquals(
            ConversationListUiAction.MessageIdentity(identity),
            actions.singleOrNull(),
        )
    }

    @Test
    fun `a display-only rendering neither opens the detail nor offers Save`() = runComposeUiTest {
        setContent {
            HomebaseTheme(darkTheme = false) {
                MessageBubbleRaw(
                    message = message(),
                    decryptedFiles = persistentMapOf(),
                    sentByYou = true,
                    onLongClick = {},
                    onMediaClick = {},
                    onClickMessageId = {},
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null,
                    downloadingFiles = emptySet(),
                    onSaveContactCard = { },
                    displayOnly = true,
                )
            }
        }

        onNodeWithText("Ada Vance").assertHasNoClickAction()
        onNodeWithText(saveLabel).assertDoesNotExist()
    }
}
