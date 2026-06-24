package id.homebase.core.location.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/** Why a transient consumer wants GPS running (for logging/diagnostics). */
enum class DemandReason {
    /** The live-location map is open and wants the user's own dot. */
    LiveMapOpen,

    /** A one-shot `getCurrentGps()` is in flight. */
    CurrentFixRequest,

    /** The "share my current location" composer is fetching a fix. */
    ShareComposer,
}

/** Released by a transient GPS consumer when it no longer needs GPS. Idempotent. */
fun interface DemandToken {
    fun release()
}

/**
 * The set of reasons GPS should be running, as pure, testable state. The historical two reasons —
 * the **history** master switch and an **active live share** — are passed into [wants] (they live in
 * preferences / the share service, not here). On top of those this adds **transient demands**: a
 * refcounted hold a foreground consumer (e.g. the live map, a one-shot fix) acquires so GPS runs
 * without enabling location history. This is what decouples "see myself now" from "record my
 * history" (the #841 coupling).
 *
 * Thread-safe via `MutableStateFlow` CAS — acquire/release may be called from any thread (VM init /
 * onCleared on main, the coordinator's scope, etc.).
 */
class GpsDemand {
    private val tokens = MutableStateFlow<Map<Long, DemandReason>>(emptyMap())
    private val nextId = MutableStateFlow(0L)

    /** GPS should run when history is allowed, OR a live share needs it, OR a transient hold is held. */
    fun wants(allowHistory: Boolean, liveShare: Boolean): Boolean =
        allowHistory || liveShare || tokens.value.isNotEmpty()

    /** Whether any transient hold is currently held. */
    fun hasTransient(): Boolean = tokens.value.isNotEmpty()

    /** Reasons for the currently-held transient holds (diagnostics). */
    fun activeReasons(): Collection<DemandReason> = tokens.value.values

    fun acquire(reason: DemandReason): Long {
        val id = nextId.updateAndGet { it + 1 }
        tokens.update { it + (id to reason) }
        return id
    }

    /** Release a previously-acquired token. No-op if already released / unknown. */
    fun release(token: Long) {
        tokens.update { it - token }
    }
}
