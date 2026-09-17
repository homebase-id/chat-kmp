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
import id.homebase.chat.services.ReplyContext
import id.homebase.chat.services.ReplyPreview
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class InlineReplyPreviewAudioTest {

    private val pngThumb = EmbeddedThumb(
        pixelWidth = 1,
        pixelHeight = 1,
        contentType = "image/png",
        content = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC",
    )

    private val voiceNote = message(
        PayloadDescriptor(
            key = "chat_web0",
            contentType = "audio/mp4",
            descriptorContent = DescriptorContent.descriptorContentFromAudioFile("rec.m4a", 15),
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
        previewThumbnail = pngThumb,
        payloads = persistentListOf(*payloads),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )

    private fun reply(thumb: EmbeddedThumb?, context: ReplyContext.Audio? = null) = ReplyPreview(
        replyUniqueId = voiceNote.id,
        authorOdinId = "alice.example.com",
        message = "",
        previewThumbnail = thumb,
        context = context?.let { ReplyContext.audio(it.lengthSeconds) },
    )

    @Test
    fun legacyReplyToLoadedVoiceNote_showsVoiceLabel_andNoThumbnail() = runComposeUiTest {
        setContent {
            MaterialTheme {
                InlineReplyPreview(
                    replyPreview = reply(thumb = pngThumb),
                    sentByYou = false,
                    onClick = {},
                    replyMessage = voiceNote,
                    driveId = Uuid.random(),
                )
            }
        }
        onNodeWithTag(ChatBubbleTestTags.REPLY_QUOTE_TEXT, useUnmergedTree = true)
            .assertTextEquals("Voice message · 00:15")
        onNodeWithContentDescription("Reply thumbnail").assertDoesNotExist()
    }

    @Test
    fun audioContextReply_withoutLoadedParent_showsVoiceLabel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                InlineReplyPreview(
                    replyPreview = reply(thumb = null, context = ReplyContext.Audio(15)),
                    sentByYou = false,
                    onClick = {},
                    replyMessage = null,
                    driveId = Uuid.random(),
                )
            }
        }
        onNodeWithTag(ChatBubbleTestTags.REPLY_QUOTE_TEXT, useUnmergedTree = true)
            .assertTextEquals("Voice message · 00:15")
        onNodeWithContentDescription("Reply thumbnail").assertDoesNotExist()
    }

    @Test
    fun imageReply_withoutLoadedParent_stillShowsEmbeddedThumbnail() = runComposeUiTest {
        setContent {
            MaterialTheme {
                InlineReplyPreview(
                    replyPreview = reply(thumb = pngThumb),
                    sentByYou = false,
                    onClick = {},
                    replyMessage = null,
                    driveId = Uuid.random(),
                )
            }
        }
        onNodeWithContentDescription("Reply thumbnail").assertExists()
    }
}
