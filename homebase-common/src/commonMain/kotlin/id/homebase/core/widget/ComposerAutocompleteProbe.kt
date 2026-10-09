package id.homebase.core.widget

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.runtime.tooling.IdentifiableRecomposeScope
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Diagnostic for #1859: after every applied composition of a [ComposerAutocomplete], reads its
 * restart group's slots back through the tooling API and checks them against the layout the code
 * should produce. Off unless [install]ed (web only). Remove once #1859 is understood.
 */
object ComposerAutocompleteProbe {

    interface Reporter {
        /** The ring buffer, rewritten whenever it gains an entry, so a later trap still has it. */
        fun onTrace(trace: String)

        fun onMismatch(report: String)
    }

    private const val RingSize = 20
    private val log = Logger.withTag("AutocompleteProbe")

    private var reporter: Reporter? = null
    private val ring = ArrayDeque<String>(RingSize)
    private val lastObserved = HashMap<String, String>()
    private var applies = 0L
    private var mismatches = 0
    private var probeFailures = 0

    fun install(reporter: Reporter) {
        this.reporter = reporter
    }

    internal fun uninstall() {
        reporter = null
        ring.clear()
        lastObserved.clear()
        applies = 0
        mismatches = 0
        probeFailures = 0
    }

    @OptIn(ComposeToolingApi::class)
    internal fun afterApply(
        scope: RecomposeScope,
        composer: Composer,
        caller: String,
        enabled: Boolean,
        queryPresent: Boolean,
    ) {
        if (reporter == null) return
        val slots = try {
            val anchor = (scope as? IdentifiableRecomposeScope)?.identity ?: return
            composer.compositionData.find(anchor)?.data?.toList() ?: return
        } catch (e: Throwable) {
            // The tooling read is the probe's own; a slot table it can't read must not become a new freeze.
            if (probeFailures++ == 0) log.w(e) { "slot read failed (caller=$caller)" }
            return
        }
        check(caller, enabled, queryPresent, slots)
    }

    internal fun check(caller: String, enabled: Boolean, queryPresent: Boolean, slots: List<Any?>) {
        val reporter = reporter ?: return
        applies++
        record(caller, enabled, queryPresent, slots, reporter)
        val mismatch = findSlotMismatch(enabled, slots) ?: return
        if (mismatches++ > 0) return
        val report = buildString {
            appendLine("ComposerAutocomplete slot mismatch (#1859) caller=$caller apply=$applies")
            appendLine("slot[${mismatch.index}] expected ${mismatch.expected}, actual ${mismatch.actual}")
            appendLine("slots(enabled=$enabled, n=${slots.size}): ${slots.joinToString(", ") { describeSlot(it) }}")
            appendLine("ring (oldest first):")
            ring.forEach { appendLine("  $it") }
        }
        log.e { report }
        reporter.onMismatch(report)
    }

    private fun record(caller: String, enabled: Boolean, queryPresent: Boolean, slots: List<Any?>, reporter: Reporter) {
        val suggestions = (slots.getOrNull(SuggestionsSlot) as? MutableState<*>)?.value as? List<*>
        val visible = slots.getOrNull(VisibleStateSlot) as? MutableTransitionState<*>
        val shown = slots.getOrNull(ShownSlot) as? MutableState<*>
        val observed = buildString {
            append("enabled=").append(enabled)
            append(" query=").append(queryPresent)
            if (enabled) {
                append(" suggestions=").append(suggestions?.size ?: "?")
                append(" shown=").append(if (shown == null) "?" else if (shown.value == null) "null" else "set")
                append(" visible=").append(visible?.let { "${it.currentState}->${it.targetState}" } ?: "?")
            }
            append(" slots=").append(slots.size)
        }
        if (lastObserved[caller] == observed) return
        lastObserved[caller] = observed
        if (ring.size == RingSize) ring.removeFirst()
        ring.addLast("#$applies $caller $observed")
        reporter.onTrace(ring.joinToString("\n"))
    }
}

/**
 * One line at the top of [ComposerAutocomplete]: no group and no slot of its own (the lambda is
 * kept out of the slot table by [DontMemoize]), so probing does not change what it measures.
 */
@OptIn(ExperimentalRichTextApi::class)
@Suppress("NOTHING_TO_INLINE")
@Composable
internal inline fun ProbeComposerAutocompleteSlots(state: RichTextState, triggerId: String, enabled: Boolean) {
    val scope = currentRecomposeScope
    val composer = currentComposer
    SideEffect(
        @DontMemoize {
            ComposerAutocompleteProbe.afterApply(
                scope = scope,
                composer = composer,
                caller = triggerId,
                enabled = enabled,
                queryPresent = state.activeTriggerQuery?.triggerId == triggerId,
            )
        }
    )
}

internal data class SlotMismatch(val index: Int, val expected: String, val actual: String)

private class SlotExpectation(val description: String, val matches: (Any?) -> Boolean)

private inline fun <reified V> slot(description: String, nullable: Boolean = false) =
    SlotExpectation(if (nullable) "$description?" else description) { it is V || (nullable && it == null) }

private fun named(simpleName: String) = SlotExpectation(simpleName) { it != null && it::class.simpleName == simpleName }

// Read back from runtime 1.10.5 (JVM), restart-group data only. Effects have no group of their own
// here, so their keys, memoized lambdas and holders sit flat between the remembers.
private val LeadingLayout = listOf(
    named("RecomposeScopeImpl"),
    slot<RichTextState>("RichTextState"),
    slot<ComposerAutocompleteController>("ComposerAutocompleteController"),
    slot<Function<*>>("lambda"),
)

private val EnabledLayout: List<SlotExpectation> = LeadingLayout + listOf(
    SlotExpectation("Boolean(true)") { it == true },
    slot<RichTextState>("RichTextState"), // DisposableEffect lambda memo key
    slot<Function<*>>("lambda"), // DisposableEffect lambda
    slot<RichTextState>("RichTextState"), // DisposableEffect key1
    slot<String>("String"), // DisposableEffect key2 triggerId
    slot<Char>("Char"), // DisposableEffect key3 triggerChar
    named("RememberObserverHolder"), // DisposableEffectImpl
    slot<MutableState<*>>("MutableState<List>"), // suggestions
    named("TriggerQuery").orNull(), // LaunchedEffect lambda memo key: query
    slot<Function<*>>("lambda"), // LaunchedEffect lambda memo key: suggestionsFor
    slot<Function<*>>("lambda"), // LaunchedEffect lambda
    slot<String>("String", nullable = true), // LaunchedEffect key query?.query
    named("RememberObserverHolder"), // LaunchedEffectImpl
    slot<MutableTransitionState<*>>("MutableTransitionState"), // visibleState, the line-110 read
    slot<MutableState<*>>("MutableState<Pair?>"), // shown
)

private val DisabledLayout: List<SlotExpectation> = LeadingLayout + SlotExpectation("Boolean(false)") { it == false }

private fun SlotExpectation.orNull() = SlotExpectation("$description?") { it == null || matches(it) }

// Index-addressed reads in record(); keep in step with EnabledLayout.
private const val SuggestionsSlot = 11
private const val VisibleStateSlot = 17
private const val ShownSlot = 18

internal fun findSlotMismatch(enabled: Boolean, slots: List<Any?>): SlotMismatch? {
    val layout = if (enabled) EnabledLayout else DisabledLayout
    layout.forEachIndexed { index, expectation ->
        if (index >= slots.size) return SlotMismatch(index, expectation.description, "<missing>")
        if (!expectation.matches(slots[index])) {
            return SlotMismatch(index, expectation.description, describeSlot(slots[index]))
        }
    }
    if (slots.size > layout.size) return SlotMismatch(layout.size, "<end>", describeSlot(slots[layout.size]))
    return null
}

// Class names, sizes and booleans only: slot values hold user text (queries, contact names).
internal fun describeSlot(slot: Any?): String = when (slot) {
    null -> "null"
    is Boolean, is Int, is Long, is Char -> "${slot::class.simpleName}($slot)"
    is CharSequence -> "${slot::class.simpleName}(len=${slot.length})"
    is MutableTransitionState<*> -> "MutableTransitionState(${slot.currentState}->${slot.targetState})"
    is MutableState<*> -> "MutableState<${describeValue(slot.value)}>"
    is Function<*> -> "lambda"
    else -> slot::class.simpleName ?: "<anonymous>"
}

private fun describeValue(value: Any?): String = when (value) {
    null -> "null"
    is Collection<*> -> "${value::class.simpleName}(size=${value.size})"
    is Pair<*, *> -> "Pair<${describeValue(value.first)}, ${describeValue(value.second)}>"
    else -> describeSlot(value)
}
