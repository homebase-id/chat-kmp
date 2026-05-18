package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ReplyPreviewBarTest {

    private fun testMessage(
        content: String = "Hello world",
        authorDomain: String = "alice.example.com",
        displayName: String = "Alice",
    ): MessageUiModel = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = content,
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = OdinId(authorDomain),
        sender = OdinId(authorDomain),
        displayName = displayName,
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader(
            iv = ByteArray(16),
            aesKey = SecureByteArray(ByteArray(16)),
        ),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )

    @Test
    fun replyPreviewBarHasTestTag() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCurrentOdinId provides "me.example.com") {
                    ReplyPreviewBar(
                        message = testMessage(),
                        onDismiss = {},
                    )
                }
            }
        }
        onNodeWithTag("reply_preview_bar").assertExists()
    }

    @Test
    fun dismissCallbackFires() = runComposeUiTest {
        var dismissed = false
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCurrentOdinId provides "me.example.com") {
                    ReplyPreviewBar(
                        message = testMessage(),
                        onDismiss = { dismissed = true },
                    )
                }
            }
        }
        onNodeWithTag("reply_dismiss").performClick()
        assertTrue(dismissed, "onDismiss callback should have been invoked")
    }
}
