package id.homebase.core.widget

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.runtime.tooling.IdentifiableRecomposeScope
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Diagnostic for #1859: after every applied composition of a [ComposerAutocomplete], reads its
 * restart group's slots back through the tooling API. Each caller's first layout per `enabled`
 * becomes its baseline, and a later composition whose slot types differ from it is reported.
 * While composing, it also records which parameters differ from the caller's last applied
 * composition, so a composition that traps before it applies still leaves that behind.
 * Off unless [install]ed (web only). Remove once #1859 is understood.
 */
object ComposerAutocompleteProbe {

    interface Reporter {
        /** The ring buffer, rewritten whenever it gains an entry, so a later trap still has it. */
        fun onTrace(trace: String)

        fun onMismatch(report: String)

        /** Each caller's latest composition, rewritten on every composition. */
        fun onComposing(composing: String)
    }

    private const val RingSize = 20
    private const val MaxReports = 5
    private val log = Logger.withTag("AutocompleteProbe")

    private var reporter: Reporter? = null
    private val ring = ArrayDeque<String>(RingSize)
    private val lastObserved = HashMap<String, String>()
    private val baselines = HashMap<String, List<String?>>()
    private val composedParams = HashMap<String, List<Pair<String, Int>>>()
    private val appliedParams = HashMap<String, List<Pair<String, Int>>>()
    private val composing = LinkedHashMap<String, String>()
    private var applies = 0L
    private var reports = 0
    private var probeFailures = 0

    val installed: Boolean get() = reporter != null

    fun install(reporter: Reporter) {
        this.reporter = reporter
    }

    internal fun uninstall() {
        reporter = null
        ring.clear()
        lastObserved.clear()
        baselines.clear()
        composedParams.clear()
        appliedParams.clear()
        composing.clear()
        applies = 0
        reports = 0
        probeFailures = 0
    }

    // hashCode mirrors what changed()/changedInstance() compare: equals for values, identity for lambdas.
    internal fun whileComposing(caller: String, enabled: Boolean, queryPresent: Boolean, params: List<Pair<String, Any?>>) {
        val reporter = reporter ?: return
        val now = params.map { (name, value) -> name to value.hashCode() }
        composedParams[caller] = now
        val applied = appliedParams[caller]
        val changed = when {
            applied == null -> "none applied yet"
            else -> now.zip(applied).filter { (a, b) -> a != b }.joinToString(", ") { it.first.first }.ifEmpty { "none" }
        }
        composing[caller] = "after apply #$applies: $caller enabled=$enabled query=$queryPresent, params changed since its last apply: $changed"
        reporter.onComposing(composing.values.joinToString("\n"))
    }

    internal fun markApplied(caller: String) {
        composedParams[caller]?.let { appliedParams[caller] = it }
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
        markApplied(caller)
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
        val key = "$caller enabled=$enabled"
        val types = slots.map(::slotType)
        val baseline = baselines[key]
        if (baseline == null) {
            baselines[key] = types
            return
        }
        if (sameLayout(baseline, types)) {
            if (null in baseline) baselines[key] = baseline.zip(types) { known, now -> known ?: now }
            return
        }
        if (reports++ >= MaxReports) return
        val report = buildString {
            appendLine("ComposerAutocomplete slot layout changed (#1859) caller=$key apply=$applies")
            appendLine("baseline (n=${baseline.size}): ${baseline.joinToString(", ") { it ?: "null" }}")
            appendLine("now      (n=${types.size}): ${types.joinToString(", ") { it ?: "null" }}")
            appendLine("ring (oldest first):")
            ring.forEach { appendLine("  $it") }
        }
        log.e { report }
        reporter.onMismatch(report)
    }

    private fun record(caller: String, enabled: Boolean, queryPresent: Boolean, slots: List<Any?>, reporter: Reporter) {
        val observed = "enabled=$enabled query=$queryPresent [${slots.joinToString(", ") { describeSlot(it) }}]"
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
internal inline fun ProbeComposerAutocompleteSlots(
    state: RichTextState,
    controller: ComposerAutocompleteController,
    triggerId: String,
    triggerChar: Char,
    suggestionsFor: Any,
    replacementFor: Any,
    modifier: Modifier,
    enabled: Boolean,
    itemContent: Any,
) {
    if (ComposerAutocompleteProbe.installed) {
        ComposerAutocompleteProbe.whileComposing(
            caller = triggerId,
            enabled = enabled,
            queryPresent = state.activeTriggerQuery?.triggerId == triggerId,
            params = listOf(
                "state" to state,
                "controller" to controller,
                "triggerId" to triggerId,
                "triggerChar" to triggerChar,
                "suggestionsFor" to suggestionsFor,
                "replacementFor" to replacementFor,
                "modifier" to modifier,
                "enabled" to enabled,
                "itemContent" to itemContent,
            ),
        )
    }
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

// A null slot (an absent query, an unset key) matches any type.
internal fun sameLayout(baseline: List<String?>, now: List<String?>): Boolean =
    baseline.size == now.size && baseline.indices.all { i ->
        baseline[i] == null || now[i] == null || baseline[i] == now[i]
    }

private fun slotType(slot: Any?): String? = when (slot) {
    null -> null
    is Function<*> -> "lambda"
    else -> slot::class.simpleName ?: "<anonymous>"
}

// Types, booleans and transition states only: slot values hold user text (queries, contact names).
internal fun describeSlot(slot: Any?): String = when (slot) {
    is Boolean, is Int, is Long, is Char -> "${slot::class.simpleName}($slot)"
    is MutableTransitionState<*> -> "MutableTransitionState(${slot.currentState}->${slot.targetState})"
    else -> slotType(slot) ?: "null"
}
