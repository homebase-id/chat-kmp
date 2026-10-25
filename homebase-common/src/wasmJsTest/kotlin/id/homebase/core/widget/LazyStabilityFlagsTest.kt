package id.homebase.core.widget

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LazyStabilityFlagsTest {

    // Without initLazyStabilityFlags this traps with "illegal cast" at ComposerAutocomplete's
    // visibleState and the test times out: RichTextState's flag flips between the two calls.
    @Test
    fun aCallerReRunAfterRichTextStateFileInitKeepsTheSlotLayout() = runComposeUiTest {
        initLazyStabilityFlags()
        // Built like the chat composer's: the constructor does not run RichTextState.kt's file initialiser.
        val state = RichTextState()
        var targets by mutableStateOf(listOf("alice", "bob"), neverEqualPolicy())
        var initRichTextStateFile by mutableStateOf(false)
        setContent {
            // In the app the first edit runs this initialiser; rememberRichTextState runs it too.
            if (initRichTextStateFile) rememberRichTextState()
            MentionLikeCaller(state, rememberComposerAutocompleteController(), targets)
        }
        waitForIdle()
        initRichTextStateFile = true
        waitForIdle()
        // An equal but new list, as a members flow re-emits: the caller runs again with state unchanged.
        targets = listOf("alice", "bob")
        waitForIdle()
    }
}

@Composable
private fun MentionLikeCaller(
    state: RichTextState,
    controller: ComposerAutocompleteController,
    targets: List<String>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ComposerAutocomplete(
        state = state,
        controller = controller,
        triggerId = "mention",
        triggerChar = '@',
        suggestionsFor = { query -> targets.filter { it.startsWith(query) } },
        replacementFor = { "@$it " },
        modifier = modifier,
        enabled = enabled && targets.isNotEmpty(),
    ) { item, _ -> Text(item) }
}
