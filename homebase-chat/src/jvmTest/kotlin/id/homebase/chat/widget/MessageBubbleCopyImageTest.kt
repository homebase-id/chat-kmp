package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import com.russhwolf.settings.PreferencesSettings
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.contacts.AvatarResolver
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.Base64Bytes
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.MessageAppData
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class)
class MessageBubbleCopyImageTest {

    private val koin = koinApplication {
        modules(module {
            single { AvatarResolver(MutableStateFlow(emptyList())) }
            single { UserPreferences(PreferencesSettings(Preferences.userRoot().node("/id/homebase/test"))) }
            single { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
            single { LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob())) }
        })
    }

    private fun payload(key: String, contentType: String, uploaded: Boolean = true) = PayloadDescriptor(
        key = key,
        contentType = contentType,
        iv = if (uploaded) Base64Bytes(ByteArray(16)) else null,
        previewThumbnail = ThumbnailDescriptor(
            pixelWidth = 400, pixelHeight = 300, contentType = "image/jpeg", content = Base64Bytes(ByteArray(0)),
        ),
    )

    private fun message(vararg payloads: PayloadDescriptor) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = "",
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = OdinId("alice.example.com"),
        sender = OdinId("alice.example.com"),
        displayName = "Alice",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = persistentListOf(*payloads),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )

    private fun ComposeUiTest.show(content: @Composable () -> Unit) = setContent {
        KoinIsolatedContext(koin) {
            HomebaseTheme(darkTheme = false) {
                CompositionLocalProvider(LocalCurrentOdinId provides "me.example.com") {
                    Box(Modifier.width(400.dp)) { content() }
                }
            }
        }
    }

    @Composable
    private fun Bubble(message: MessageUiModel, onCopyImage: (String) -> Unit) = ReceivedMessageBubble(
        message = message,
        userDefaultReactions = persistentListOf(),
        decryptedFiles = persistentMapOf(),
        onMessageInfo = {},
        onDelete = {},
        onMarkAsRead = {},
        onMediaClick = {},
        onClickMessageId = {},
        downloadingFiles = emptySet(),
        onCopyImage = onCopyImage,
    )

    @Test
    fun rightClickOnAnImageOpensTheMenuWithCopyImage() = runComposeUiTest {
        val copied = mutableListOf<String>()
        show { Bubble(message(payload("chat_img0", "image/jpeg")), onCopyImage = { copied += it }) }
        onNodeWithTag(ChatBubbleTestTags.MEDIA).performMouseInput { rightClick(center) }
        onNodeWithText("Copy image").performClick()
        assertEquals(listOf("chat_img0"), copied)
    }

    @Test
    fun rightClickOnTheSecondImageOfAGalleryCopiesThatImage() = runComposeUiTest {
        val copied = mutableListOf<String>()
        show {
            Bubble(
                message(payload("chat_img0", "image/jpeg"), payload("chat_img1", "image/png")),
                onCopyImage = { copied += it },
            )
        }
        onNodeWithTag(ChatBubbleTestTags.MEDIA).performMouseInput { rightClick(percentOffset(0.75f, 0.5f)) }
        onNodeWithText("Copy image").performClick()
        assertEquals(listOf("chat_img1"), copied)
    }

    @Test
    fun galleryCopyTargetsOnlyTheImageTheMenuWasOpenedOn() {
        val gallery = message(payload("chat_img0", "image/jpeg"), payload("chat_vid1", "video/mp4"))
        assertEquals("chat_img0", gallery.copyImageTarget("chat_img0"))
        assertNull(gallery.copyImageTarget("chat_vid1"))
        assertNull(gallery.copyImageTarget(null))
        assertEquals("chat_img0", message(payload("chat_img0", "image/png")).copyImageTarget(null))
    }

    @Test
    fun ctrlOrCmdCInTheViewerCopiesTheCurrentPage() = runComposeUiTest {
        val copied = mutableListOf<String>()
        show {
            SharedTransitionLayout {
                AnimatedVisibility(visible = true) {
                    FullScreenMediaViewer(
                        data = FullScreenOverlay.ViewMessageData(
                            messageId = Uuid.random(),
                            title = "Alice",
                            userDate = Instant.fromEpochMilliseconds(0),
                            content = "",
                            fileId = Uuid.random(),
                            driveId = Uuid.random(),
                            payloads = listOf(
                                payload("chat_img0", "image/jpeg", uploaded = false),
                                payload("chat_img1", "image/jpeg", uploaded = false),
                            ),
                            keyHeader = KeyHeader.empty(),
                            selectedPayloadKey = "chat_img1",
                        ),
                        onCopyImage = { _, key -> copied += key },
                        onDismiss = {},
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this,
                    )
                }
            }
        }
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.C) } }
        onRoot().performKeyInput { pressKey(Key.C) }
        assertEquals(listOf("chat_img1", "chat_img1"), copied)
    }

    @Test
    fun copyableImageKeyIsOnlyForASingleImage() {
        assertEquals("chat_img0", message(payload("chat_img0", "image/webp")).copyableImagePayloadKey)
        assertNull(message(payload("chat_vid0", "video/mp4")).copyableImagePayloadKey)
        assertNull(message(payload("chat_img0", "image/png"), payload("chat_img1", "image/png")).copyableImagePayloadKey)
        assertNull(message(payload("chat_img0", "image/png")).copy(isPendingSend = true).copyableImagePayloadKey)
    }
}
