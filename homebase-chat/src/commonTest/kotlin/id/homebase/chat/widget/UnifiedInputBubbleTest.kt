package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class UnifiedInputBubbleTest {

    @Test
    fun showsAttachmentFabWhenTextIsEmpty() = runComposeUiTest {
        setContent {
            MaterialTheme {
                UnifiedInputBubble(
                    replyToMessage = null,
                    onDismissReply = {},
                    editExistingMode = false,
                    showSendButton = false,
                    isRecordingActive = false,
                    onSendMessage = {},
                    onCancelEdit = {},
                    onAddAttachmentClick = {},
                    modifier = androidx.compose.ui.Modifier,
                ) {}
            }
        }
        onNodeWithTag("attachment_fab").assertExists()
        onNodeWithTag("send_fab").assertDoesNotExist()
        onNodeWithTag("confirm_fab").assertDoesNotExist()
        onNodeWithTag("cancel_fab").assertDoesNotExist()
    }

    @Test
    fun showsSendFabWhenTextIsPresent() = runComposeUiTest {
        setContent {
            MaterialTheme {
                UnifiedInputBubble(
                    replyToMessage = null,
                    onDismissReply = {},
                    editExistingMode = false,
                    showSendButton = true,
                    isRecordingActive = false,
                    onSendMessage = {},
                    onCancelEdit = {},
                    onAddAttachmentClick = {},
                    modifier = androidx.compose.ui.Modifier,
                ) {}
            }
        }
        onNodeWithTag("send_fab").assertExists()
        onNodeWithTag("attachment_fab").assertDoesNotExist()
        onNodeWithTag("cancel_fab").assertDoesNotExist()
    }

    @Test
    fun showsConfirmAndCancelFabsInEditMode() = runComposeUiTest {
        setContent {
            MaterialTheme {
                UnifiedInputBubble(
                    replyToMessage = null,
                    onDismissReply = {},
                    editExistingMode = true,
                    showSendButton = true,
                    isRecordingActive = false,
                    onSendMessage = {},
                    onCancelEdit = {},
                    onAddAttachmentClick = {},
                    modifier = androidx.compose.ui.Modifier,
                ) {}
            }
        }
        onNodeWithTag("confirm_fab").assertExists()
        onNodeWithTag("cancel_fab").assertExists()
        onNodeWithTag("send_fab").assertDoesNotExist()
        onNodeWithTag("attachment_fab").assertDoesNotExist()
    }

    @Test
    fun hidesAllFabsDuringRecording() = runComposeUiTest {
        setContent {
            MaterialTheme {
                UnifiedInputBubble(
                    replyToMessage = null,
                    onDismissReply = {},
                    editExistingMode = false,
                    showSendButton = false,
                    isRecordingActive = true,
                    onSendMessage = {},
                    onCancelEdit = {},
                    onAddAttachmentClick = {},
                    modifier = androidx.compose.ui.Modifier,
                ) {}
            }
        }
        onNodeWithTag("attachment_fab").assertDoesNotExist()
        onNodeWithTag("send_fab").assertDoesNotExist()
        onNodeWithTag("cancel_fab").assertDoesNotExist()
        onNodeWithTag("confirm_fab").assertDoesNotExist()
    }

    @Test
    fun hidesReplyPreviewWhenNull() = runComposeUiTest {
        setContent {
            MaterialTheme {
                UnifiedInputBubble(
                    replyToMessage = null,
                    onDismissReply = {},
                    editExistingMode = false,
                    showSendButton = false,
                    isRecordingActive = false,
                    onSendMessage = {},
                    onCancelEdit = {},
                    onAddAttachmentClick = {},
                    modifier = androidx.compose.ui.Modifier,
                ) {}
            }
        }
        onNodeWithTag("reply_preview_bar").assertDoesNotExist()
    }

    @Test
    fun showsEditLabelInEditMode() = runComposeUiTest {
        setContent {
            MaterialTheme {
                UnifiedInputBubble(
                    replyToMessage = null,
                    onDismissReply = {},
                    editExistingMode = true,
                    showSendButton = true,
                    isRecordingActive = false,
                    onSendMessage = {},
                    onCancelEdit = {},
                    onAddAttachmentClick = {},
                    modifier = androidx.compose.ui.Modifier,
                ) {}
            }
        }
        onNodeWithTag("edit_message_label").assertExists()
    }

    @Test
    fun hidesEditLabelWhenNotEditing() = runComposeUiTest {
        setContent {
            MaterialTheme {
                UnifiedInputBubble(
                    replyToMessage = null,
                    onDismissReply = {},
                    editExistingMode = false,
                    showSendButton = false,
                    isRecordingActive = false,
                    onSendMessage = {},
                    onCancelEdit = {},
                    onAddAttachmentClick = {},
                    modifier = androidx.compose.ui.Modifier,
                ) {}
            }
        }
        onNodeWithTag("edit_message_label").assertDoesNotExist()
    }

    private fun testMessage(): MessageUiModel = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = "Test message",
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = OdinId("alice.example.com"),
        sender = OdinId("alice.example.com"),
        displayName = "Alice",
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
    fun showsReplyPreviewWhenReplyMessagePresent() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCurrentOdinId provides "me.example.com") {
                    UnifiedInputBubble(
                        replyToMessage = testMessage(),
                        onDismissReply = {},
                        editExistingMode = false,
                        showSendButton = false,
                        isRecordingActive = false,
                        onSendMessage = {},
                        onCancelEdit = {},
                        onAddAttachmentClick = {},
                    ) {}
                }
            }
        }
        onNodeWithTag("reply_preview_bar").assertExists()
    }

    @Test
    fun sendFabInvokesOnSendMessage() = runComposeUiTest {
        var sent = false
        setContent {
            MaterialTheme {
                UnifiedInputBubble(
                    replyToMessage = null,
                    onDismissReply = {},
                    editExistingMode = false,
                    showSendButton = true,
                    isRecordingActive = false,
                    onSendMessage = { sent = true },
                    onCancelEdit = {},
                    onAddAttachmentClick = {},
                ) {}
            }
        }
        onNodeWithTag("send_fab").performClick()
        assertTrue(sent)
    }

    @Test
    fun cancelFabInvokesOnCancelEdit() = runComposeUiTest {
        var cancelled = false
        setContent {
            MaterialTheme {
                UnifiedInputBubble(
                    replyToMessage = null,
                    onDismissReply = {},
                    editExistingMode = true,
                    showSendButton = true,
                    isRecordingActive = false,
                    onSendMessage = {},
                    onCancelEdit = { cancelled = true },
                    onAddAttachmentClick = {},
                ) {}
            }
        }
        onNodeWithTag("cancel_fab").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun attachmentFabInvokesOnAddAttachmentClick() = runComposeUiTest {
        var attached = false
        setContent {
            MaterialTheme {
                UnifiedInputBubble(
                    replyToMessage = null,
                    onDismissReply = {},
                    editExistingMode = false,
                    showSendButton = false,
                    isRecordingActive = false,
                    onSendMessage = {},
                    onCancelEdit = {},
                    onAddAttachmentClick = { attached = true },
                ) {}
            }
        }
        onNodeWithTag("attachment_fab").performClick()
        assertTrue(attached)
    }
}
