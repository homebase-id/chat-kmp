package id.homebase.core.widget

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Same simple names as the runtime/richeditor classes the layout matches by name.
private class RecomposeScopeImpl
private class RememberObserverHolder
private class TriggerQuery

@OptIn(ExperimentalTestApi::class)
class ComposerAutocompleteProbeTest {

    private val mismatches = mutableListOf<String>()
    private var trace = ""

    @BeforeTest
    fun setUp() = ComposerAutocompleteProbe.install(object : ComposerAutocompleteProbe.Reporter {
        override fun onTrace(trace: String) {
            this@ComposerAutocompleteProbeTest.trace = trace
        }

        override fun onMismatch(report: String) {
            mismatches += report
        }
    })

    @AfterTest
    fun tearDown() = ComposerAutocompleteProbe.uninstall()

    private fun enabledSlots(query: Any? = TriggerQuery()): MutableList<Any?> {
        val state = RichTextState()
        return mutableListOf(
            RecomposeScopeImpl(), true, state, {}, state, "mention", '@', RememberObserverHolder(),
            mutableStateOf(listOf("a", "b")), query, {}, {}, "fro", RememberObserverHolder(),
            MutableTransitionState(false), mutableStateOf(null),
        )
    }

    @Test
    fun aShiftedLayoutIsReportedWithTheRingBuffer() {
        ComposerAutocompleteProbe.check("mention", enabled = false, queryPresent = false, slots = listOf(RecomposeScopeImpl(), false))
        ComposerAutocompleteProbe.check("mention", enabled = true, queryPresent = true, slots = enabledSlots())
        ComposerAutocompleteProbe.check("mention", enabled = true, queryPresent = false, slots = enabledSlots(query = null))
        assertTrue(mismatches.isEmpty(), "a stable layout must not report: $mismatches")

        val shifted = enabledSlots().also { it.add(2, mutableStateOf("secret query text")) }
        ComposerAutocompleteProbe.check("mention", enabled = true, queryPresent = true, slots = shifted)

        val report = mismatches.single()
        assertTrue("caller=mention enabled=true apply=4" in report, report)
        assertTrue("baseline (n=16): RecomposeScopeImpl, Boolean, RichTextState, lambda," in report, report)
        assertTrue("now      (n=17): RecomposeScopeImpl, Boolean(true), MutableState<String(len=17)>, RichTextState," in report, report)
        assertTrue("#1 mention enabled=false query=false [RecomposeScopeImpl, Boolean(false)]" in report, report)
        assertTrue("MutableTransitionState(false->false)" in report, report)
        assertFalse("secret" in report, "slot values must never be logged: $report")
        assertEquals(4, trace.lines().size)
    }

    @Test
    fun eachCallerAndEnabledStateHasItsOwnBaseline() {
        ComposerAutocompleteProbe.check("mention", enabled = true, queryPresent = false, slots = enabledSlots())
        ComposerAutocompleteProbe.check("emoji", enabled = true, queryPresent = false, slots = enabledSlots().drop(2))
        ComposerAutocompleteProbe.check("mention", enabled = false, queryPresent = false, slots = listOf(RecomposeScopeImpl(), false))
        assertTrue(mismatches.isEmpty(), mismatches.joinToString())
    }

    @Test
    fun reportsStopAfterTheCap() {
        ComposerAutocompleteProbe.check("mention", enabled = true, queryPresent = false, slots = enabledSlots())
        repeat(8) {
            ComposerAutocompleteProbe.check("mention", enabled = true, queryPresent = false, slots = enabledSlots().drop(1))
        }
        assertEquals(5, mismatches.size)
    }

    @Test
    fun aNullSlotMatchesAnyType() {
        assertTrue(sameLayout(listOf("A", null, "C"), listOf("A", "B", "C")))
        assertTrue(sameLayout(listOf("A", "B", "C"), listOf("A", null, "C")))
        assertFalse(sameLayout(listOf("A", "B", "C"), listOf("A", "C", "B")))
        assertFalse(sameLayout(listOf("A", "B"), listOf("A", "B", "C")))
    }

    /** A real caller's compositions must keep one layout per state, or the probe reports noise. */
    @Test
    fun theRealCompositionKeepsOneLayoutPerState() = runComposeUiTest {
        lateinit var state: RichTextState
        var enabled by mutableStateOf(true)
        setContent {
            MaterialTheme {
                state = rememberRichTextState()
                val controller = rememberComposerAutocompleteController()
                Box {
                    RichTextEditor(state = state)
                    ComposerAutocomplete(
                        state = state,
                        controller = controller,
                        triggerId = "test",
                        triggerChar = ':',
                        suggestionsFor = { q -> listOf("smile", "smirk").filter { it.startsWith(q) } },
                        replacementFor = { it },
                        enabled = enabled,
                    ) { item, _ -> Text(item) }
                }
            }
        }
        runOnIdle { state.addTextAfterSelection(":sm") }
        waitForIdle()
        runOnIdle { enabled = false }
        waitForIdle()
        runOnIdle { enabled = true }
        waitForIdle()
        runOnIdle { state.setText("") }
        waitForIdle()

        assertTrue(mismatches.isEmpty(), mismatches.joinToString())
        assertTrue("MutableTransitionState(true->true)" in trace, trace)
        assertTrue("enabled=false query=false [" in trace, trace)
    }
}
