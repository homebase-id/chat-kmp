package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

@OptIn(ExperimentalTestApi::class)
class MessageBubbleCopyImageTest {

    private val koin = koinApplication {
        modules(module {
            single { AvatarResolver(MutableStateFlow(emptyList())) }
            single { UserPreferences(PreferencesSettings(Preferences.userRoot().node("/id/homebase/test"))) }
            single { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
            single { LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob())) }
        })
    }

    private fun payload(key: String, contentType: String) = PayloadDescriptor(
        key = key,
        contentType = contentType,
        iv = Base64Bytes(ByteArray(16)),
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

    @Test
    fun rightClickOnAnImageOpensTheMenuWithCopyImage() = runComposeUiTest {
        var copied = 0
        show {
            ReceivedMessageBubble(
                message = message(payload("chat_img0", "image/jpeg")),
                userDefaultReactions = persistentListOf(),
                decryptedFiles = persistentMapOf(),
                onMessageInfo = {},
                onDelete = {},
                onMarkAsRead = {},
                onMediaClick = {},
                onClickMessageId = {},
                downloadingFiles = emptySet(),
                onCopyImage = { copied++ },
            )
        }
        onNodeWithTag(ChatBubbleTestTags.MEDIA).performMouseInput { rightClick(center) }
        onNodeWithText("Copy image").performClick()
        assertEquals(1, copied)
    }

    @Test
    fun copyableImageKeyIsOnlyForASingleImage() {
        assertEquals("chat_img0", message(payload("chat_img0", "image/webp")).copyableImagePayloadKey)
        assertNull(message(payload("chat_vid0", "video/mp4")).copyableImagePayloadKey)
        assertNull(message(payload("chat_img0", "image/png"), payload("chat_img1", "image/png")).copyableImagePayloadKey)
        assertNull(message(payload("chat_img0", "image/png")).copy(isPendingSend = true).copyableImagePayloadKey)
    }
}
