package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.util.initials
import id.homebase.core.widget.ComposerAutocompleteTag
import id.homebase.core.widget.rememberComposerAutocompleteController
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The suggestion list re-renders on every keystroke while you filter, and each row now draws a
 * `ContactAvatar`. Asserts on Coil requests rather than on the tree: both avatar branches clear
 * their semantics, so a screen-level assertion cannot tell a fetch from initials.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalRichTextApi::class)
class MentionAvatarFetchTest {

    private val requested = CopyOnWriteArrayList<Any>()

    private val recorder = Interceptor { chain ->
        requested += chain.request.data
        ErrorResult(null, chain.request, UnsupportedOperationException("recorded"))
    }

    private val koin = koinApplication {
        modules(
            module {
                single {
                    ImageLoader.Builder(PlatformContext.INSTANCE)
                        .components { add(recorder) }
                        .build()
                }
            },
        )
    }

    private fun member(handle: String, name: String) =
        ContactUiModel.fallbackFor(OdinId(handle)).copy(name = name, avatarInitials = name.initials())

    private val group = listOf(
        member("alice.example.com", "Alice Anderson"),
        member("bob.example.com", "Bob Brown"),
        member("carol.example.com", "Carol Clark"),
    )

    private fun harness(capture: (RichTextState) -> Unit): @Composable () -> Unit = {
        KoinIsolatedContext(koin) {
            MaterialTheme {
                val state = rememberRichTextState()
                val controller = rememberComposerAutocompleteController()
                capture(state)
                Box {
                    RichTextEditor(state = state, modifier = Modifier.testTag("editor"))
                    MentionAutocomplete(state = state, controller = controller, targets = group)
                }
            }
        }
    }

    private fun publicImageHosts(): List<String> = requested.mapNotNull { data ->
        val url = when (data) {
            is String -> data
            is Uri -> data.toString()
            else -> return@mapNotNull null
        }
        url.takeIf { it.contains("/pub/image") }
            ?.removePrefix("https://")
            ?.substringBefore("/pub/image")
    }

    @Test
    fun eachIdentityIsDialedOncePerListing() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("@") }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasTestTag(ComposerAutocompleteTag)).fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(
            listOf("alice.example.com", "bob.example.com", "carol.example.com"),
            publicImageHosts().sorted(),
        )
    }

    /**
     * Typing on narrows the list but leaves Alice in it. The URL is the Coil model, so the request
     * stays equal across those recompositions and the painter never restarts: a keystroke that does
     * not change who is on screen costs no load at all.
     */
    @Test
    fun typingOnDoesNotRedialAnIdentityThatStaysInTheList() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("@") }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasTestTag(ComposerAutocompleteTag)).fetchSemanticsNodes().isNotEmpty()
        }
        for (char in "alic") {
            runOnIdle { state.addTextAfterSelection(char.toString()) }
            waitForIdle()
        }

        assertEquals(
            1,
            publicImageHosts().count { it == "alice.example.com" },
            "five renderings of Alice's row must not be five loads; got ${publicImageHosts()}",
        )
    }
}
