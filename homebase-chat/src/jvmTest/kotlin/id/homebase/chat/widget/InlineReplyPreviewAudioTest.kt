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

    private fun message(contentType: String, descriptorContent: String) = MessageUiModel(
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
            PayloadDescriptor(key = "chat_web0", contentType = contentType, descriptorContent = descriptorContent),
        ),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )

    private fun audio(name: String, lengthSeconds: Int) =
        message("audio/mp4", DescriptorContent.descriptorContentFromAudioFile(name, lengthSeconds))

    private fun assertQuote(replyMessage: MessageUiModel?, expectedText: String?, expectThumbnail: Boolean) =
        runComposeUiTest {
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
                        replyMessage = replyMessage,
                        driveId = Uuid.random(),
                    )
                }
            }
            if (expectedText != null) {
                onNodeWithTag(ChatBubbleTestTags.REPLY_QUOTE_TEXT, useUnmergedTree = true)
                    .assertTextEquals(expectedText)
            }
            val thumbnail = onNodeWithContentDescription("Reply thumbnail")
            if (expectThumbnail) thumbnail.assertExists() else thumbnail.assertDoesNotExist()
        }

    @Test
    fun voiceNote_showsVoiceLabelWithDuration_andNoThumbnail() =
        assertQuote(audio("recording-1.m4a", 15), "Voice message · 00:15", expectThumbnail = false)

    @Test
    fun audioFileWithoutLength_showsAudioLabel_notVoiceMessage() =
        assertQuote(audio("song.mp3", 0), "Audio", expectThumbnail = false)

    @Test
    fun pdf_showsFileName_andNoThumbnail() =
        assertQuote(message("application/pdf", "report.pdf"), "report.pdf", expectThumbnail = false)

    @Test
    fun replyWithoutLoadedParent_stillShowsEmbeddedThumbnail() =
        assertQuote(replyMessage = null, expectedText = null, expectThumbnail = true)
}
