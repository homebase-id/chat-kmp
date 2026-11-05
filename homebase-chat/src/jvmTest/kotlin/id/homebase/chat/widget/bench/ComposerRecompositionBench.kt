package id.homebase.chat.widget.bench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.russhwolf.settings.PreferencesSettings
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.link.LinkPreviewProvider
import id.homebase.chat.widget.MessageInputBar
import id.homebase.chat.widget.applyFormatShortcut
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.widget.ComposerKeyAction
import id.homebase.core.widget.composerKeyHandler
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.Assume.assumeTrue
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.prefs.Preferences
import kotlin.test.Test

/**
 * Counts restart scopes entered per keystroke with the runtime's CompositionObserver. A scope is
 * named after the first app or library frame on the stack when it is first composed (the body
 * calls startRestartGroup); a recomposition started by the recomposer has no such frame, so the
 * name recorded at first composition is reused. Skipped unless `CHAT_UI_BENCH=1`.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeRuntimeApi::class, InternalComposeApi::class)
class ComposerRecompositionBench {

    private fun requireBench() =
        assumeTrue("Set CHAT_UI_BENCH=1 to run the chat UI benchmarks", System.getenv("CHAT_UI_BENCH") == "1")

    private class ScopeCounter : CompositionObserver, CompositionRegistrationObserver {
        var recording = false
        val counts = HashMap<String, Int>()
        private val names = java.util.IdentityHashMap<RecomposeScope, String>()
        private val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ObservableComposition, Boolean>())
        private var registered = false

        fun attach(composition: Any) {
            if (registered) return
            registered = true
            onCompositionRegistered(composition as ObservableComposition)
            val parent = composition.javaClass.getMethod("getParent").invoke(composition)
            if (parent is Recomposer) parent.observe(this)
        }

        fun reset() = counts.clear()
        val total get() = counts.values.sum()

        override fun onCompositionRegistered(composition: ObservableComposition) {
            if (seen.add(composition)) composition.setObserver(this)
        }
        override fun onCompositionUnregistered(composition: ObservableComposition) {}
        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onReadInScope(scope: RecomposeScope, value: Any) {}
        override fun onScopeExit(scope: RecomposeScope) {}
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
        override fun onScopeDisposed(scope: RecomposeScope) { names.remove(scope) }

        override fun onScopeEnter(scope: RecomposeScope) {
            val frame = Thread.currentThread().stackTrace.firstOrNull {
                val c = it.className
                !c.startsWith("java.") && !c.startsWith("jdk.") && !c.startsWith("androidx.compose.runtime.") &&
                    !c.startsWith("androidx.compose.ui.scene.") && !c.startsWith("kotlin.") &&
                    !c.contains("ComposerRecompositionBench\$ScopeCounter")
            }
            frame?.let { names.putIfAbsent(scope, "${it.className.substringAfterLast('.')}.${it.methodName}") }
            if (!recording) return
            val name = names[scope] ?: "<unnamed>"
            counts[name] = (counts[name] ?: 0) + 1
        }
    }

    private val koin = koinApplication {
        modules(
            module {
                single { UserPreferences(PreferencesSettings(Preferences.userRoot().node("/id/homebase/bench"))) }
                single { LinkPreviewProvider(HttpClient(MockEngine { respond("") }), CredentialsManager()) }
            }
        )
    }

    @Composable
    private fun Attach(counter: ScopeCounter) {
        val composition = currentComposer.composition
        remember { counter.attach(composition) }
    }

    private fun ComposeUiTest.typeAndCount(counter: ScopeCounter, chars: Int, state: () -> RichTextState): Pair<Int, Int> {
        val field = onAllNodes(hasSetTextAction()).onFirst()
        field.requestFocus()
        waitForIdle()
        val before = state().annotatedString.text.length
        counter.reset()
        counter.recording = true
        repeat(chars) {
            field.performTextInput("a")
            waitForIdle()
        }
        counter.recording = false
        return counter.total to (state().annotatedString.text.length - before)
    }

    private fun breakdown(counter: ScopeCounter, chars: Int): String =
        counter.counts.entries.sortedByDescending { it.value }
            .joinToString("\n") { "  %6.2f  %s".format(it.value.toDouble() / chars, it.key) }

    @Test
    fun messageInputBarScopesPerKeystroke() {
        requireBench()
        val chars = 40
        runDesktopComposeUiTest(width = 1200, height = 620) {
            val counter = ScopeCounter()
            lateinit var state: RichTextState
            var show by mutableStateOf(false)
            setContent {
                Attach(counter)
                if (show) KoinIsolatedContext(koin) {
                    HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            state = rememberRichTextState()
                            MessageInputBar(
                                textFieldState = state,
                                recordingData = null,
                                focusRequester = remember { FocusRequester() },
                                editExistingMode = false,
                                showingEmojiSheet = false,
                                onEmojiClick = {},
                                onKeyboardClick = {},
                                onFocused = {},
                                onAddAttachmentClick = {},
                                onCameraClick = {},
                                onRecordingStarted = {},
                                onRecordingStopped = {},
                                onRecordingCancelled = {},
                                onRecordingHelp = {},
                                payloadRenderers = emptyList(),
                                onPayloadRenderersChange = {},
                                onSendMessage = { _, _ -> },
                                onCancelEdit = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            show = true
            waitForIdle()
            typeAndCount(counter, 5) { state }
            val (total, inserted) = typeAndCount(counter, chars) { state }
            println(
                "\n### MessageInputBar, performTextInput(\"a\") x $chars " +
                    "(inserted $inserted chars)\nscopes entered per keystroke: %.2f\n".format(total.toDouble() / chars) +
                    breakdown(counter, chars)
            )
        }
    }

    private enum class Handler { BoundReference, RememberedLambda, NoOnFormat, NoKeyHandler }

    @Composable
    private fun Editor(state: RichTextState, tick: Int, handler: Handler) {
        val label = tick.toString()
        Text(label)
        val remembered = remember(state) { { a: ComposerKeyAction -> state.applyFormatShortcut(a) } }
        val modifier = when (handler) {
            Handler.BoundReference -> Modifier.composerKeyHandler(enterSendsMessage = true, onSend = {}, onFormat = state::applyFormatShortcut)
            Handler.RememberedLambda -> Modifier.composerKeyHandler(enterSendsMessage = true, onSend = {}, onFormat = remembered)
            Handler.NoOnFormat -> Modifier.composerKeyHandler(enterSendsMessage = true, onSend = {})
            Handler.NoKeyHandler -> Modifier
        }
        RichTextEditor(state = state, modifier = modifier.testTag("editor"))
    }

    @Test
    fun formatHandlerAttribution() {
        requireBench()
        val ticks = 40
        val rows = mutableListOf<List<String>>()
        for (handler in Handler.entries) {
            runDesktopComposeUiTest(width = 800, height = 400) {
                val counter = ScopeCounter()
                var tick by mutableIntStateOf(0)
                lateinit var state: RichTextState
                var show by mutableStateOf(false)
                setContent {
                    Attach(counter)
                    if (show) MaterialTheme {
                        state = rememberRichTextState()
                        Column { Editor(state, tick, handler) }
                    }
                }
                waitForIdle()
                show = true
                waitForIdle()
                onNodeWithTag("editor").requestFocus()
                runOnIdle { state.addTextAfterSelection("hello") }
                waitForIdle()
                counter.reset()
                counter.recording = true
                repeat(ticks) { tick++; waitForIdle() }
                counter.recording = false
                val editorScopes = counter.counts.filterKeys { it.contains("RichTextEditor") || it.contains("BasicTextField") || it.contains("CoreTextField") || it.contains("BasicRichTextEditor") }
                println("\n$handler, parent recomposes x $ticks:\n" + breakdown(counter, ticks))
                val (typed, _) = typeAndCount(counter, 20) { state }
                rows += listOf(
                    handler.name,
                    "%.2f".format(editorScopes.values.sum().toDouble() / ticks),
                    "%.2f".format(typed / 20.0),
                )
            }
        }
        report(
            "Editor scopes entered when only the parent recomposes, by onFormat argument",
            listOf("onFormat", "editor scopes per parent recomposition", "all scopes per keystroke"),
            rows,
        )
    }
}
