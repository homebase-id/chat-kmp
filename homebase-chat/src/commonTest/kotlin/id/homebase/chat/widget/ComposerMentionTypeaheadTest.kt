package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.util.initials
import id.homebase.core.widget.ComposerAutocompleteTag
import id.homebase.core.widget.EmojiAutocomplete
import id.homebase.core.widget.rememberComposerAutocompleteController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun member(handle: String, name: String) =
    ContactUiModel.fallbackFor(OdinId(handle)).copy(name = name, avatarInitials = name.initials())

private val GroupMembers = listOf(
    member("alice.example.com", "Alice Anderson"),
    member("bob.example.com", "Bob Brown"),
)

/**
 * The row's `ContactAvatar` resolves its Coil loader through Koin, so a bare `runComposeUiTest`
 * dies with "KoinApplication has not been started". This one fails every request, which puts every
 * row on the initials fallback: deterministic, and it dials nobody.
 */
private class OfflineImageLoader : ImageLoader {
    override val defaults: ImageRequest.Defaults = ImageRequest.Defaults.DEFAULT
    override val components: ComponentRegistry = ComponentRegistry()
    override val memoryCache: MemoryCache? = null
    override val diskCache: DiskCache? = null

    override fun enqueue(request: ImageRequest): Disposable = object : Disposable {
        override val job: Deferred<ImageResult> = CompletableDeferred(refuse(request))
        override val isDisposed: Boolean = true
        override fun dispose() = Unit
    }

    override suspend fun execute(request: ImageRequest): ImageResult = refuse(request)

    override fun shutdown() = Unit

    override fun newBuilder(): ImageLoader.Builder = throw UnsupportedOperationException()

    private fun refuse(request: ImageRequest) =
        ErrorResult(null, request, UnsupportedOperationException("offline test loader"))
}

private val TestKoin = koinApplication {
    modules(module { single<ImageLoader> { OfflineImageLoader() } })
}

@OptIn(ExperimentalTestApi::class, ExperimentalRichTextApi::class)
class ComposerMentionTypeaheadTest {

    /** Mirrors the composer: a Box wrapping only the editor anchors the popup, and the editor's
     *  preview-key handler gives the autocomplete first refusal before Enter-to-send. */
    private fun harness(
        targets: List<ContactUiModel> = GroupMembers,
        withEmoji: Boolean = false,
        onSend: () -> Unit = {},
        capture: (RichTextState) -> Unit,
    ): @Composable () -> Unit = {
        KoinIsolatedContext(TestKoin) {
            MaterialTheme {
                val state = rememberRichTextState()
                val controller = rememberComposerAutocompleteController()
                capture(state)
                Box {
                    RichTextEditor(
                        state = state,
                        modifier = Modifier
                            .onPreviewKeyEvent { event ->
                                if (controller.handleKeyEvent(event)) {
                                    true
                                } else if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                    onSend()
                                    true
                                } else {
                                    false
                                }
                            }
                            .testTag("editor"),
                    )
                    MentionAutocomplete(state = state, controller = controller, targets = targets)
                    if (withEmoji) {
                        EmojiAutocomplete(state = state, controller = controller, enabled = true)
                    }
                }
            }
        }
    }

    /** [topSpacer] null pushes the composer to the bottom of the window; a small value starves it
     *  of room above. */
    private fun spacedHarness(
        topSpacer: androidx.compose.ui.unit.Dp?,
        capture: (RichTextState) -> Unit,
    ): @Composable () -> Unit = {
        KoinIsolatedContext(TestKoin) {
            MaterialTheme {
                val state = rememberRichTextState()
                val controller = rememberComposerAutocompleteController()
                capture(state)
                Column(Modifier.fillMaxSize()) {
                    if (topSpacer == null) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(topSpacer))
                    Box(Modifier.fillMaxWidth().testTag("anchor")) {
                        RichTextEditor(state = state, modifier = Modifier.fillMaxWidth())
                        MentionAutocomplete(
                            state = state,
                            controller = controller,
                            targets = GroupMembers,
                        )
                    }
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

    /** Waits for the popup itself, for cases where the query text is also in the editor. */
    private fun androidx.compose.ui.test.ComposeUiTest.awaitSuggestions() =
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasTestTag(ComposerAutocompleteTag)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun androidx.compose.ui.test.ComposeUiTest.assertNoText(text: String) =
        assertEquals(0, onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size)

    @Test
    fun aBareTriggerListsTheGroupMembers() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("@") }
        awaitText("Alice Anderson")

        onNodeWithText("Bob Brown", substring = true).assertExists()
    }

    /** No published photo is the common case for a connection, so the row has to stay legible on
     *  the initials alone. The test loader refuses every request, which is that path. */
    @Test
    fun aRowWithNoPhotoFallsBackToTheContactsInitials() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("@") }
        awaitText("Alice Anderson")

        onNodeWithText("AA").assertExists()
        onNodeWithText("BB").assertExists()
    }

    /**
     * The name is what the user reads; the handle is the confirmation. Unweighted, the handle is
     * measured first at the popup's full width and leaves the name a stub — with the avatar in the
     * row the name fell to "Wil…" beside a fully drawn 32-character handle.
     */
    @Test
    fun aLongHandleDoesNotStarveTheName() = runComposeUiTest {
        lateinit var state: RichTextState
        val long = member("verylongidentityname.example.com", "Wilhelmina Featherstonehaugh-Smythe")
        setContent(harness(targets = listOf(long)) { state = it })

        runOnIdle { state.addTextAfterSelection("@") }
        awaitSuggestions()

        val name = onNodeWithText("Wilhelmina", substring = true, useUnmergedTree = true)
            .getBoundsInRoot().width
        val handle = onNodeWithText("verylongidentityname", substring = true, useUnmergedTree = true)
            .getBoundsInRoot().width
        assertTrue(
            name >= handle * 0.7f,
            "name got ${name}, handle got ${handle}",
        )
    }

    @Test
    fun typingFiltersTheList() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("@bo") }
        awaitText("Bob Brown")

        assertNoText("Alice Anderson")
    }

    /** The 1:1 gate: no members, no trigger, no affordance. */
    @Test
    fun noMembersMeansTheTriggerNeverOpens() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness(targets = emptyList()) { state = it })

        runOnIdle { state.addTextAfterSelection("@ali") }
        waitForIdle()

        assertNoText("Alice Anderson")
        assertEquals("@ali", state.annotatedString.text)
        // Not merely "no list": the trigger is never registered, so richeditor does not paint the
        // live `@ali` token as a pending mention either.
        assertNull(state.activeTriggerQuery)
    }

    @Test
    fun committingInsertsTheHandleAndLeavesTheCaretAfterIt() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("hey @ali") }
        awaitText("Alice Anderson")

        onNodeWithText("Alice Anderson", substring = true).performClick()
        waitForIdle()

        assertEquals("hey @alice.example.com ", state.annotatedString.text)
        assertEquals(TextRange(state.annotatedString.text.length), state.selection)
    }

    /** The plain-text mention has to survive the markdown the composer actually sends. */
    @Test
    fun theCommittedMentionSurvivesTheMarkdownSerialisation() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("hey @ali") }
        awaitText("Alice Anderson")

        onNodeWithText("Alice Anderson", substring = true).performClick()
        waitForIdle()

        assertTrue(
            state.toMarkdown().contains("@alice.example.com"),
            "got: ${state.toMarkdown()}",
        )
    }

    /** Display names and message text carry emoji, so the commit goes through the shared
     *  surrogate-safe splice rather than a raw replaceTextRange. */
    @Test
    fun commitIsSurrogateSafeAroundAdjacentEmoji() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("🎉 @ali") }
        awaitText("Alice Anderson")

        onNodeWithText("Alice Anderson", substring = true).performClick()
        waitForIdle()

        assertEquals("🎉 @alice.example.com ", state.annotatedString.text)
    }

    @Test
    fun theTriggerNeverOpensInsideAWord() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("write to bob@ali") }
        waitForIdle()

        assertNoText("Alice Anderson")
    }

    /** A fully typed handle drops out of the list, so Enter still sends the message. */
    @Test
    fun aFullyTypedHandleLeavesEnterFreeToSend() = runComposeUiTest {
        lateinit var state: RichTextState
        var sends = 0
        setContent(harness(onSend = { sends++ }) { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("hey @alice.example.com") }
        waitForIdle()

        onNodeWithTag("editor").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(1, sends)
    }

    /**
     * The handle is the wire form; a display name is not. So typing `@Sebastian` in full has to
     * leave the list open and let Enter commit `@yagni.dk`, or the message goes out carrying a
     * mention no client can resolve.
     */
    @Test
    fun aFullyTypedDisplayNameStillCommitsTheHandle() = runComposeUiTest {
        lateinit var state: RichTextState
        var sends = 0
        setContent(
            harness(targets = listOf(member("yagni.dk", "Sebastian")), onSend = { sends++ }) {
                state = it
            }
        )

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("hey @Sebastian") }
        awaitSuggestions()

        onNodeWithTag("editor").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(0, sends, "Enter must commit the mention, not send @Sebastian")
        assertEquals("hey @yagni.dk ", state.annotatedString.text)
    }

    @Test
    fun arrowKeysAndEnterCommitTheHighlightedMember() = runComposeUiTest {
        lateinit var state: RichTextState
        var sends = 0
        setContent(harness(onSend = { sends++ }) { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("@") }
        awaitText("Bob Brown")

        onNodeWithTag("editor").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag("editor").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(0, sends, "Enter must commit the member, not send the message")
        assertEquals("@bob.example.com ", state.annotatedString.text)
    }

    /**
     * Tapping a row must not move focus off the editor — on mobile that focus is what holds the
     * soft keyboard open. This asserts the focus only; no IME runs in a JVM test.
     */
    @Test
    fun tappingASuggestionLeavesFocusOnTheEditor() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("@ali") }
        awaitText("Alice Anderson")
        onNodeWithTag("editor").assertIsFocused()

        onNodeWithText("Alice Anderson", substring = true).performClick()
        waitForIdle()

        onNodeWithTag("editor").assertIsFocused()
        assertEquals("@alice.example.com ", state.annotatedString.text)
    }

    /**
     * A composer pinned to the bottom is the geometry a soft keyboard produces, and the one the
     * list has to survive on mobile: it must open into the space above the composer, not under it.
     */
    @Test
    fun theListOpensAboveAComposerPinnedToTheBottom() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(spacedHarness(topSpacer = null) { state = it })

        runOnIdle { state.addTextAfterSelection("@") }
        awaitText("Alice Anderson")

        val anchor = onNodeWithTag("anchor").getBoundsInRoot()
        val list = onNodeWithTag(ComposerAutocompleteTag).getBoundsInRoot()
        assertTrue(
            list.bottom <= anchor.top,
            "list must sit above the composer; list=$list anchor=$anchor",
        )
    }

    /** With no room above, the list flips below the anchor rather than being clipped off-screen. */
    @Test
    fun theListFlipsBelowWhenThereIsNoRoomAbove() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(spacedHarness(topSpacer = 40.dp) { state = it })

        runOnIdle { state.addTextAfterSelection("@") }
        awaitText("Alice Anderson")

        val anchor = onNodeWithTag("anchor").getBoundsInRoot()
        val list = onNodeWithTag(ComposerAutocompleteTag).getBoundsInRoot()
        assertTrue(
            list.top >= anchor.bottom,
            "list must fall back below the composer; list=$list anchor=$anchor",
        )
    }

    /** Both triggers live on one RichTextState and share one key controller. */
    @Test
    fun theMentionAndEmojiTriggersCoexist() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness(withEmoji = true) { state = it })

        runOnIdle { state.addTextAfterSelection("@ali") }
        awaitText("Alice Anderson")

        runOnIdle { state.addTextAfterSelection(" ") }
        waitForIdle()
        assertNoText("Alice Anderson")

        runOnIdle { state.addTextAfterSelection(":par") }
        awaitText(":party:")
    }
}
