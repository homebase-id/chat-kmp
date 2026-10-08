package id.homebase.chat.viewonce

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.conversationlist.toReplyPreview
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.mapToMessageData
import id.homebase.chat.widget.ChatBubbleTestTags
import id.homebase.chat.widget.InlineReplyPreview
import id.homebase.chat.widget.ReplyPreviewBar
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class ViewOnceReplyQuoteTest {

    private val now = Clock.System.now().toEpochMilliseconds()

    private val thumb = EmbeddedThumb(
        pixelWidth = 1,
        pixelHeight = 1,
        contentType = "image/png",
        content = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC",
    )

    private suspend fun unopened(kind: String): MessageUiModel {
        val json = """{\"schemaVersion\":1,\"kind\":\"$kind\",\"caption\":\"secret caption\"}"""
        return mapToMessageData(viewOnceHeader(createdMs = now - DAY_MS, content = json), ownerCredentials())!!
            .copy(previewThumbnail = thumb)
    }

    @Test
    fun theReplyThatRidesOnTheWireIsTheKindWordOnly() = runTest {
        for ((kind, word) in listOf("image" to "Photo", "video" to "Video")) {
            val preview = unopened(kind).toReplyPreview()

            assertEquals(word, preview.message)
            assertNull(preview.previewThumbnail)
            assertFalse(OdinSystemSerializer.serialize(preview).contains("secret"), "the caption must not travel in the quote")
        }
    }

    @Test
    fun theComposerBarQuotesTheKindWithTheViewOnceIconAndNoThumbnail() = runTest {
        val message = unopened("video")
        runComposeUiTest {
            setContent { MaterialTheme { ReplyPreviewBar(message = message, onDismiss = {}) } }

            onNodeWithText("Video").assertExists()
            assertEquals(0, onAllNodesWithText("secret", substring = true).fetchSemanticsNodes().size)
            onNodeWithContentDescription("Reply thumbnail").assertDoesNotExist()
        }
    }

    @Test
    fun aQuoteWhoseOriginalIsInMemoryShowsTheIconAndKind() = runTest {
        for ((kind, word) in listOf("image" to "Photo", "video" to "Video")) {
            val original = unopened(kind)
            val preview = original.toReplyPreview()
            runComposeUiTest {
                setContent {
                    MaterialTheme {
                        InlineReplyPreview(replyPreview = preview, sentByYou = true, onClick = {}, replyMessage = original, driveId = Uuid.random())
                    }
                }
                onNodeWithTag(ChatBubbleTestTags.REPLY_QUOTE_TEXT, useUnmergedTree = true).assertTextEquals(word)
                onNodeWithTag(ChatBubbleTestTags.REPLY_QUOTE_ICON, useUnmergedTree = true).assertExists()
            }
        }
    }

    @Test
    fun aQuoteOfAnOpenedItemNamesTheAuthorByTheResolvedDisplayName() = runTest {
        val tombstone = assertNotNull(
            mapToMessageData(
                serverTombstone(createdMs = now - 2 * DAY_MS, updatedMs = now - DAY_MS),
                ownerCredentials(),
                displayNameResolver = { "Samwise Gamgee" },
            ),
        )
        assertEquals("Samwise Gamgee", tombstone.displayName)
        val preview = unopened("image").toReplyPreview().copy(authorOdinId = VO_SENDER)
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    InlineReplyPreview(replyPreview = preview, sentByYou = false, onClick = {}, replyMessage = tombstone, driveId = Uuid.random())
                }
            }
            onNodeWithText("Samwise Gamgee").assertExists()
            onNodeWithTag(ChatBubbleTestTags.REPLY_QUOTE_ICON, useUnmergedTree = true).assertExists()
        }
    }
}
