package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.dismissKeyboardOnTap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.skiko.hostOs
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Desktop drag-select + copy inside a chat bubble, alone and inside the conversation list. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class BubbleTextSelectionTest {

    private val body = "Selectable words inside one chat bubble"

    private class FakeClipboard : Clipboard {
        var entry: ClipEntry? = null
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }
        override val nativeClipboard = java.awt.datatransfer.Clipboard("test")

        fun text(): String? = (entry?.nativeClipEntry as? Transferable)
            ?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
            ?.getTransferData(DataFlavor.stringFlavor) as? String
    }

    private class FakeUriHandler : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private fun message(content: String) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = content,
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = OdinId("alice.example.com"),
        sender = OdinId("alice.example.com"),
        displayName = "Alice",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = persistentListOf(),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )

    @Composable
    private fun Bubble(content: String) {
        MessageBubbleRaw(
            message = message(content),
            decryptedFiles = persistentMapOf(),
            sentByYou = false,
            onLongClick = {},
            onMediaClick = {},
            onClickMessageId = {},
            sharedTransitionScope = null,
            animatedVisibilityScope = null,
            downloadingFiles = emptySet(),
        )
    }

    private fun ComposeUiTest.render(
        clipboard: Clipboard,
        uriHandler: UriHandler = FakeUriHandler(),
        content: @Composable () -> Unit,
    ) = setContent {
        HomebaseTheme(darkTheme = false) {
            CompositionLocalProvider(
                LocalCurrentOdinId provides "me.example.com",
                LocalClipboard provides clipboard,
                LocalUriHandler provides uriHandler,
            ) {
                Box(Modifier.width(400.dp)) { content() }
            }
        }
    }

    // Same list modifier ConversationContent puts on the message LazyColumn.
    @Composable
    private fun InConversationList(content: @Composable () -> Unit) {
        LazyColumn(Modifier.fillMaxSize().dismissKeyboardOnTap()) {
            item { content() }
        }
    }

    private fun ComposeUiTest.dragAcrossCaptionAndCopy() {
        onNodeWithTag(ChatBubbleTestTags.CAPTION).performMouseInput {
            val y = height / 2f
            moveTo(Offset(1f, y))
            press()
            for (step in 1..10) moveTo(Offset(width * 0.7f * step / 10f, y))
            release()
        }
        waitForIdle()
        val copyModifier = if (hostOs.isMacOS) Key.MetaLeft else Key.CtrlLeft
        onRoot().performKeyInput { withKeyDown(copyModifier) { pressKey(Key.C) } }
        waitForIdle()
    }

    private fun assertCopiedPartOfBody(clipboard: FakeClipboard) {
        val copied = clipboard.text()
        assertTrue(
            !copied.isNullOrEmpty() && body.contains(copied),
            "drag-select + copy should put part of \"$body\" on the clipboard, got: $copied",
        )
    }

    @Test
    fun dragSelectAndCopy_bubbleAlone() = runComposeUiTest {
        val clipboard = FakeClipboard()
        render(clipboard) { Bubble(body) }
        dragAcrossCaptionAndCopy()
        assertCopiedPartOfBody(clipboard)
    }

    @Test
    fun dragSelectAndCopy_insideConversationList() = runComposeUiTest {
        val clipboard = FakeClipboard()
        render(clipboard) { InConversationList { Bubble(body) } }
        dragAcrossCaptionAndCopy()
        assertCopiedPartOfBody(clipboard)
    }

    @Test
    fun linkClickStillOpensInsideConversationList() = runComposeUiTest {
        val uriHandler = FakeUriHandler()
        render(FakeClipboard(), uriHandler) {
            InConversationList { Bubble("[example link](https://example.com)") }
        }
        onNodeWithTag(ChatBubbleTestTags.CAPTION).performMouseInput { click(center) }
        waitForIdle()
        assertEquals(listOf("https://example.com"), uriHandler.opened)
    }
}
