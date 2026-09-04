@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiException
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.peer.temporal.TemporalDriveReadProvider
import id.homebase.api.common.OdinId
import id.homebase.core.config.locationLabeledDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi

/** How long a probed peer is left alone before the sweep asks again. */
const val RECONCILE_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

/** Hard ceiling on preflights per pass, so cost stays flat as the contact book grows. */
const val RECONCILE_BUDGET: Int = 25

/**
 * Recovers a missed "they added me" designation — the set-only background backstop for the
 * best-effort status-message path ([EmergencyContactReceiveService]). The live status message
 * only fires on the WS-push path, so a designation that arrives during cold sync (offline →
 * login catch-up) or in a dropped event never sets the flag. Here we re-derive it by
 * preflighting the peer's location drive with
 * [TemporalDriveReadProvider.verifyTemporalAccess] (reads no data, fires no notification on the
 * peer).
 *
 * The sweep runs on every app start, so it is bounded twice (issue #1243): a peer probed within
 * [RECONCILE_TTL_MS] is skipped, and at most [RECONCILE_BUDGET] peers are probed per pass,
 * oldest-first. A 1000-contact book therefore costs 25 preflights per start instead of 1000, and
 * still cycles fully within a couple of weeks — a missed designation self-heals rather than
 * needing a gap signal to find it.
 *
 * We record the *attempt*, not the outcome: a peer that has granted us nothing answers 400, which
 * is indistinguishable from a transport failure, so there is no trustworthy negative to cache yet.
 * A 4xx is taken as "the server answered" and starts the TTL; anything else (offline, timeout,
 * 5xx) is inconclusive and is retried on the next pass rather than buying a week of silence.
 *
 * Deliberately SET-only (issue #961): a non-throwing `hasAccess = false` is NOT a trustworthy
 * revocation signal — it also fires on benign/ambiguous negatives (and has been observed after
 * the owner emptied their own *outgoing* emergency circle), so a verify-based clear silently
 * wiped the incoming "Who you can locate" list. The only path that clears `iCanLocate` is the
 * peer's explicit revocation message ([EmergencyContactReceiveService.onRevoked]); a stale flag
 * surfaces non-destructively as a broken row via the per-entry freshness check instead.
 *
 * A network/parse failure is inconclusive — we leave the flag untouched rather than flip on a
 * guess.
 *
 * Limitation: only contacts we already hold can be reconciled (a peer who added us but isn't in
 * our contact book yet has no row to flag — the status message remains the discovery path for
 * those).
 */
class EmergencyContactReconciler(
    private val contactRepository: ContactRepository,
    private val temporalRead: TemporalDriveReadProvider,
    private val attemptLog: EmergencyReconcileAttemptLog,
    private val scope: CoroutineScope,
) {
    private val locationDrive = locationLabeledDrive.drive.alias

    /** Fire-and-forget the budgeted set-only reconcile in the background (called at login). */
    fun start() {
        scope.launch { runCatching { reconcileAll() } }
    }

    /**
     * Set-only pass over identity-backed contacts: recovers a designation the live status-message
     * path missed. At most [RECONCILE_BUDGET] preflights, skipping peers probed within
     * [RECONCILE_TTL_MS].
     */
    suspend fun reconcileAll() {
        contactRepository.ensureLoaded()

        // Group by odinId, not by contact row: one identity can hold several rows (issue #982),
        // and probing each of them separately would burn the budget on the same peer.
        val flaggable = contactRepository.contacts.value.mapNotNull { contact ->
            val odinId = contact.content.odinId?.takeIf { it.isNotBlank() }?.lowercase()
                ?: return@mapNotNull null
            if (contact.versionTag == null) return@mapNotNull null
            odinId to contact
        }
        val unflagged = flaggable.filterNot { it.second.iCanLocate() }
            .groupBy({ it.first }, { it.second })

        val attempts = attemptLog.load()
        val targets = selectProbeTargets(
            candidates = unflagged.keys,
            lastAttemptMs = attempts,
            nowMs = nowMs(),
            ttlMs = RECONCILE_TTL_MS,
            budget = RECONCILE_BUDGET,
        )
        if (targets.isEmpty()) return

        val updated = attempts.toMutableMap()
        for (odinId in targets) {
            val outcome = probe(OdinId(odinId))
            if (outcome.conclusive) updated[odinId] = nowMs()
            if (reconcileAction(outcome.hasAccess, flagged = false) == ReconcileAction.Set) {
                unflagged[odinId].orEmpty().forEach { setFlag(it, odinId) }
            }
        }
        // Drop peers that have left the contact book so the log can't grow without bound.
        attemptLog.save(updated.filterKeys { key -> flaggable.any { it.first == key } })
    }

    private suspend fun setFlag(contact: Contact, odinId: String) {
        val versionTag = contact.versionTag ?: return
        runCatching { contactRepository.setICanLocate(contact.uniqueId, versionTag) }
            .onFailure { Logger.w(it) { "reconcile: setICanLocate failed for $odinId" } }
    }

    private suspend fun probe(odinId: OdinId): ProbeOutcome = try {
        ProbeOutcome(
            hasAccess = temporalRead.verifyTemporalAccess(odinId, locationDrive).hasAccess,
            conclusive = true,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ProbeOutcome(hasAccess = null, conclusive = isConclusiveFailure(e))
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private class ProbeOutcome(val hasAccess: Boolean?, val conclusive: Boolean)
}

/**
 * Whether a failed preflight still counts as "the peer path answered us", starting the TTL. A 4xx
 * is the server's verdict (today a missing grant surfaces as 400); a 5xx or a transport failure is
 * not, and must stay retryable so a spell offline can't silence a peer for a week.
 */
fun isConclusiveFailure(e: Throwable): Boolean {
    val status = (e as? OdinApiException)?.status ?: return false
    return status in 400..499
}

/**
 * The peers this pass should preflight: those never probed or probed longer ago than [ttlMs],
 * oldest-first, capped at [budget]. A stored timestamp in the future (clock moved backwards) is
 * treated as stale rather than skipped forever.
 */
fun selectProbeTargets(
    candidates: Set<String>,
    lastAttemptMs: Map<String, Long>,
    nowMs: Long,
    ttlMs: Long,
    budget: Int,
): List<String> = candidates
    .filter {
        val last = lastAttemptMs[it] ?: 0L
        last > nowMs || nowMs - last >= ttlMs
    }
    .sortedWith(compareBy<String> { lastAttemptMs[it] ?: 0L }.thenBy { it })
    .take(budget)

enum class ReconcileAction { Set, None }

/**
 * Decides what a verify-based pass does with one contact, given the preflight outcome.
 * [hasAccess] is null when the preflight threw (network/parse failure) — inconclusive, never act.
 *
 * There is deliberately NO Clear (issue #961): a non-throwing `hasAccess = false` is not a
 * trustworthy revocation, so verify-based passes must never wipe the flag. The only clear path
 * is the peer's explicit revocation ([EmergencyContactReceiveService.onRevoked] via
 * [revocationAction]).
 *
 * Emergency designation == currently holding temporal read access to the peer's location drive.
 * windowSeconds is NOT a reliable ACL-type discriminator — a real emergency-circle grant has
 * been observed reporting windowSeconds=null in practice (see issue #875) — so gate on
 * hasAccess alone.
 */
fun reconcileAction(hasAccess: Boolean?, flagged: Boolean): ReconcileAction =
    if (hasAccess == true && !flagged) ReconcileAction.Set else ReconcileAction.None
