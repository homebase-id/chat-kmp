package id.homebase.chat.contactcard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import com.russhwolf.settings.PreferencesSettings
import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.widget.MessageItem
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.prefs.Preferences
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What a contact card is allowed to dial.
 *
 * Rendering an identity avatar issues `GET https://<odinId>/pub/image`, and the odinId comes off a
 * card any client may have authored — so the assertions here are on the Coil requests the render
 * actually produces, not on what the tree looks like. Both avatar branches clear their semantics,
 * so a screen-level assertion cannot tell a fetch from initials at all.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class)
class ContactCardAvatarFetchTest {

    private val me = OdinId("me.demo.rocks")
    private val stranger = "tracker.evil.tld"
    private val friend = "samwise.gamgee.demo.rocks"

    private val requested = CopyOnWriteArrayList<Any>()

    // Records what the render asked for and fails every request: nothing here needs pixels, and a
    // real fetch would leave the test dependent on the network.
    private val recorder = Interceptor { chain ->
        requested += chain.request.data
        ErrorResult(null, chain.request, UnsupportedOperationException("recorded"))
    }

    private val preferences =
        UserPreferences(PreferencesSettings(Preferences.userRoot().node("/id/homebase/test")))

    private val koin = koinApplication {
        modules(
            module {
                single { preferences }
                single {
                    ImageLoader.Builder(PlatformContext.INSTANCE)
                        .components { add(recorder) }
                        .build()
                }
            },
        )
    }

    private fun card(odinId: String) = ContactCardDescriptor(
        displayName = "Ada Vance",
        phones = listOf("+14155550123"),
        odinId = odinId,
    )

    private val photo = HomebaseImageData(
        driveId = Uuid.random(),
        fileId = Uuid.random(),
        payloadKey = "chat_web0",
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
    )

    @Composable
    private fun Host(savedContacts: Set<OdinId>, content: @Composable () -> Unit) {
        KoinIsolatedContext(koin) {
            HomebaseTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalSavedContactIdentities provides savedContacts,
                    content = content,
                )
            }
        }
    }

    private fun ComposeUiTest.renderBubble(
        descriptor: ContactCardDescriptor,
        authorOdinId: String?,
        savedContacts: Set<OdinId> = emptySet(),
        photo: HomebaseImageData? = null,
    ) {
        setContent {
            Host(savedContacts) {
                ContactCardBubble(
                    descriptor = descriptor,
                    authorOdinId = authorOdinId,
                    photo = photo,
                )
            }
        }
        waitForIdle()
    }

    private fun publicImageHosts(): List<String> = requested.mapNotNull { data ->
        val url = when (data) {
            is String -> data
            is Uri -> data.toString()
            else -> return@mapNotNull null
        }
        url.takeIf { it.contains("/pub/image") }
            ?.removePrefix("https://")
            ?.substringBefore("/pub/image")
    }

    @Test
    fun `a card you sent naming someone off your book dials nobody`() = runComposeUiTest {
        // The forwarded card: authored by you, but the identity on it was chosen by whoever sent
        // it to you first.
        renderBubble(card(stranger), authorOdinId = me.domainName)

        assertEquals(emptyList(), publicImageHosts())
    }

    @Test
    fun `a card naming a saved contact dials that contact`() = runComposeUiTest {
        renderBubble(
            card(friend),
            authorOdinId = stranger,
            savedContacts = setOf(OdinId(friend)),
        )

        assertEquals(listOf(friend), publicImageHosts())
    }

    @Test
    fun `a card received from a stranger naming a third party dials nobody`() = runComposeUiTest {
        renderBubble(card(friend), authorOdinId = stranger)

        assertEquals(emptyList(), publicImageHosts())
    }

    @Test
    fun `a card its own subject sent dials them, book or no book`() = runComposeUiTest {
        renderBubble(card(friend), authorOdinId = friend)

        assertEquals(listOf(friend), publicImageHosts())
    }

    @Test
    fun `a card carrying a photo draws it without dialing the identity it names`() =
        runComposeUiTest {
            renderBubble(card(stranger), authorOdinId = me.domainName, photo = photo)

            assertEquals(emptyList(), publicImageHosts())
            assertTrue(
                requested.any { it is HomebaseImageData },
                "The payload bytes are already on the message; drawing them dials nobody.",
            )
        }

    @Test
    fun `a card with no identity dials nobody, however full the book is`() = runComposeUiTest {
        renderBubble(
            card(""),
            authorOdinId = friend,
            savedContacts = setOf(OdinId(friend), OdinId(stranger)),
        )

        assertEquals(emptyList(), publicImageHosts())
    }

    @Test
    fun `a host that provides no book gets no fetch`() = runComposeUiTest {
        setContent {
            KoinIsolatedContext(koin) {
                HomebaseTheme(darkTheme = false) {
                    ContactCardBubble(descriptor = card(friend), authorOdinId = null)
                }
            }
        }
        waitForIdle()

        assertEquals(emptyList(), publicImageHosts())
    }

    // Through MessageItem, not the bubble: the book crosses ConversationContent -> MessageItem ->
    // MessageBubble -> MessageBubbleRaw to reach the avatar, and a direct bubble call cannot see a
    // layer that swallows the CompositionLocal.
    @Test
    fun `the book reaches a card rendered in the stream`() = runComposeUiTest {
        setContent {
            Host(setOf(OdinId(friend))) {
                SharedTransitionLayout {
                    AnimatedVisibility(visible = true) {
                        MessageItem(
                            message = message(author = me, card = card(friend)),
                            userDefaultReactions = persistentListOf(),
                            decryptedFiles = persistentMapOf(),
                            currentOdinId = me.domainName,
                            animatedVisibilityScope = this@AnimatedVisibility,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            onUiAction = {},
                            downloadingFiles = emptySet(),
                        )
                    }
                }
            }
        }
        waitForIdle()

        assertEquals(listOf(friend), publicImageHosts())
    }

    @Test
    fun `a card you sent in the stream naming a stranger dials nobody`() = runComposeUiTest {
        setContent {
            Host(emptySet()) {
                SharedTransitionLayout {
                    AnimatedVisibility(visible = true) {
                        MessageItem(
                            message = message(author = me, card = card(stranger)),
                            userDefaultReactions = persistentListOf(),
                            decryptedFiles = persistentMapOf(),
                            currentOdinId = me.domainName,
                            animatedVisibilityScope = this@AnimatedVisibility,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            onUiAction = {},
                            downloadingFiles = emptySet(),
                        )
                    }
                }
            }
        }
        waitForIdle()

        assertEquals(emptyList(), publicImageHosts())
    }

    private fun message(author: OdinId?, card: ContactCardDescriptor) = MessageUiModel(
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
        messageContent = MessageContent.ContactCard(card),
    )
}
