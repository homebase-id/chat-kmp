package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.viewonce.ViewOnceDescriptor
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class InlineReplyPreviewProtectedMediaTest {

    private val pngThumb = EmbeddedThumb(
        pixelWidth = 1,
        pixelHeight = 1,
        contentType = "image/png",
        content = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC",
    )

    private fun message(content: MessageContent) = MessageUiModel(
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
        previewThumbnail = pngThumb,
        payloads = persistentListOf(
            PayloadDescriptor(key = "chat_web0", contentType = "image/jpeg", descriptorContent = ""),
        ),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
        messageContent = content,
    )

    private fun assertNoThumbnail(content: MessageContent) = runComposeUiTest {
        setContent {
            MaterialTheme {
                InlineReplyPreview(
                    replyPreview = ReplyPreview(
                        replyUniqueId = Uuid.random(),
                        authorOdinId = "alice.example.com",
                        message = "",
                        previewThumbnail = pngThumb,
                    ),
                    sentByYou = false,
                    onClick = {},
                    replyMessage = message(content),
                    driveId = Uuid.random(),
                )
            }
        }
        onNodeWithContentDescription("Reply thumbnail").assertDoesNotExist()
    }

    @Test
    fun viewOnceTarget_neverLoadsAnImage() =
        assertNoThumbnail(MessageContent.ViewOnce(ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE)))

    @Test
    fun unparseableViewOnceTarget_neverLoadsAnImage() = assertNoThumbnail(MessageContent.ViewOnce(null))

    @Test
    fun unknownTarget_neverLoadsAnImage() = assertNoThumbnail(MessageContent.Unknown(dataType = 999))
}
