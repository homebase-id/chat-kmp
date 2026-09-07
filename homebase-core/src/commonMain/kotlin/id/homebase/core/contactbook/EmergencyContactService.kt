@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.peer.temporal.TemporalAccessStatus
import id.homebase.api.client.peer.temporal.TemporalDriveReadProvider
import id.homebase.api.common.OdinId
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.locationLabeledDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi

/**
 * Result of the temporal-access preflight for one contact we can locate. Only a successful,
 * data-bearing [Active] earns the [LOCATE_VERIFY_TTL_MS] cache; [Broken], [Unreachable] and a
 * no-data Active re-verify on every pass so an error state clears the moment access/data returns.
 */
sealed interface LocateVerifyStatus {
    data object Loading : LocateVerifyStatus

    sealed interface Resolved : LocateVerifyStatus {
        val verifiedAtMs: Long
    }

    /** Verify succeeded but the peer no longer grants us access. */
    data class Broken(override val verifiedAtMs: Long) : Resolved

    /** We hold access; [newestModifiedMs] is the peer's newest-file time, null when no data yet. */
    data class Active(val newestModifiedMs: Long?, override val verifiedAtMs: Long) : Resolved

    /** The verify threw (network/parse) — inconclusive, must never look like [Broken]. */
    data class Unreachable(override val verifiedAtMs: Long) : Resolved
}

const val LOCATE_VERIFY_TTL_MS = 60_000L

/** How long a verify waits for the app to come online before attempting the POST (#998). */
const val LOCATE_VERIFY_ONLINE_WAIT_MS = 15_000L

/** A locatable contact whose newest data is older than this has "no tracking signal". */
const val LOCATE_STALE_WARN_MS = 2L * 24 * 60 * 60_000L

/** Flagged contacts are re-swept on reconnect at most this often. */
const val RECONNECT_RESWEEP_MIN_INTERVAL_MS = 4L * 60 * 60_000L

private const val STALE_TICK_MS = 5L * 60_000L

fun LocateVerifyStatus?.needsReverify(nowMs: Long): Boolean = when (this) {
    LocateVerifyStatus.Loading -> false
    is LocateVerifyStatus.Active ->
        newestModifiedMs == null || nowMs - verifiedAtMs >= LOCATE_VERIFY_TTL_MS
    is LocateVerifyStatus.Resolved -> true
    null -> true
}

/** A contact that never produced a data point is not stale; only a real timestamp can be. */
fun locateSignalStale(newestModifiedMs: Long?, nowMs: Long): Boolean =
    newestModifiedMs != null && nowMs - newestModifiedMs > LOCATE_STALE_WARN_MS

data class LocatableContact(
    val contact: Contact,
    val odinId: OdinId,
    val status: LocateVerifyStatus?,
)

/**
 * Single owner of "can we still locate this contact, and how fresh is their data?" — the
 * in-memory overlay every verify caller reads and writes, layered over
 * [ContactRepository.locatableContacts] the way ContactService layers connection state.
 */
class EmergencyContactService internal constructor(
    private val contacts: StateFlow<List<Contact>>,
    private val verify: suspend (OdinId) -> TemporalAccessStatus,
    private val isOnline: StateFlow<Boolean>,
    /** The logged-in identity's domain (lowercase) — you are never your own emergency contact. */
    private val selfDomain: suspend () -> String?,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        contactRepository: ContactRepository,
        temporalRead: TemporalDriveReadProvider,
        authConnectionCoordinator: AuthConnectionCoordinator,
        credentialsManager: CredentialsManager,
        scope: CoroutineScope,
    ) : this(
        contacts = contactRepository.contacts,
        verify = { peer -> temporalRead.verifyTemporalAccess(peer, locationLabeledDrive.drive.alias) },
        isOnline = authConnectionCoordinator.isOnline,
        selfDomain = {
            kotlin.runCatching { credentialsManager.getActiveDomain() }.getOrNull()?.domainName?.lowercase()
        },
        scope = scope,
    )

    private val _status = MutableStateFlow<Map<String, LocateVerifyStatus>>(emptyMap())
    val status: StateFlow<Map<String, LocateVerifyStatus>> = _status.asStateFlow()

    val locatable: StateFlow<List<LocatableContact>> =
        combine(contacts.map { it.filterLocatable() }, _status) { contacts, status ->
            val self = selfDomain()
            contacts.mapNotNull { contact ->
                val odinId = contact.content.odinId?.takeIf { it.isNotBlank() }?.let(::OdinId)
                    ?: return@mapNotNull null
                if (odinId.domainName.lowercase() == self) return@mapNotNull null
                LocatableContact(contact, odinId, status[odinId.domainName])
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val staleIds: StateFlow<Set<String>> =
        combine(locatable, staleTicker()) { list, _ ->
            val now = now()
            list.filter { entry ->
                val active = entry.status as? LocateVerifyStatus.Active
                active != null && locateSignalStale(active.newestModifiedMs, now)
            }.map { it.odinId.domainName }.toSet()
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    val hasStale: StateFlow<Boolean> =
        staleIds.map { it.isNotEmpty() }.stateIn(scope, SharingStarted.Eagerly, false)

    private var started = false
    private var lastFullSweepMs = 0L
    private val sweepMutex = Mutex()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            isOnline
                .drop(1)
                .filter { it }
                .collect {
                    if (now() - lastFullSweepMs >= RECONNECT_RESWEEP_MIN_INTERVAL_MS) {
                        runCatching { refreshAll() }
                    }
                }
        }
    }

    fun reset() {
        _status.value = emptyMap()
        lastFullSweepMs = 0L
    }

    /**
     * One preflight against [peer]'s server. Records the outcome for any peer (flagged or not).
     * A cancelled verify restores the previous value instead of leaving a stuck Loading.
     */
    suspend fun refresh(
        peer: OdinId,
        showSpinner: Boolean = false,
        waitForOnline: Boolean = true,
    ): LocateVerifyStatus {
        val key = peer.domainName
        val previous = _status.value[key]
        if (showSpinner || previous == null) put(key, LocateVerifyStatus.Loading)
        try {
            if (waitForOnline) {
                withTimeoutOrNull(LOCATE_VERIFY_ONLINE_WAIT_MS) { isOnline.first { it } }
            }
            val access = try {
                verify(peer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "verify failed for $key" }
                null
            }
            val now = now()
            val result = when {
                access == null -> LocateVerifyStatus.Unreachable(now)
                !access.hasAccess -> return recordAccessLost(peer, now)
                else -> LocateVerifyStatus.Active(
                    newestModifiedMs = access.newestFileModified.milliseconds.takeIf { it > 0 },
                    verifiedAtMs = now,
                )
            }
            put(key, result)
            return result
        } catch (e: CancellationException) {
            if (_status.value[key] == LocateVerifyStatus.Loading) {
                if (previous == null) remove(key) else put(key, previous)
            }
            throw e
        }
    }

    fun refreshAsync(peer: OdinId) {
        scope.launch { runCatching { refresh(peer) } }
    }

    /**
     * Parallel [refresh] over every contact we can locate (restricted to [only] when given),
     * skipping entries whose cached result is still fresh. Returns once all resolve.
     */
    suspend fun refreshAll(showSpinner: Boolean = false, only: Set<String>? = null) {
        // Serialized: the login sweep and the reconnect collector wake on the same online
        // transition, and the second must see the first's results (then skip inside the TTL).
        sweepMutex.withLock {
            val now = now()
            val status = _status.value
            val self = selfDomain()
            val targets = contacts.value.filterLocatable()
                .mapNotNull { it.content.odinId?.takeIf { id -> id.isNotBlank() }?.let(::OdinId) }
                .filter { it.domainName.lowercase() != self }
                .filter { only == null || it.domainName in only }
                .filter { status[it.domainName].needsReverify(now) }
            if (only == null) lastFullSweepMs = now
            Logger.i(TAG) { "refreshAll: targets=${targets.size} only=${only?.size} spinner=$showSpinner" }
            if (targets.isEmpty()) return
            coroutineScope {
                targets.forEach { peer -> launch { refresh(peer, showSpinner) } }
            }
        }
    }

    /**
     * The verify says we are no longer allowed. Recorded as a broken link (which also drops the
     * peer from [staleIds]); deliberately does NOT clear `iCanLocate` — a verify-based negative is
     * not a trustworthy revocation (#961), only the peer's revocation message clears the flag.
     */
    fun recordAccessLost(peer: OdinId, observedAtMs: Long): LocateVerifyStatus.Broken {
        val broken = LocateVerifyStatus.Broken(observedAtMs)
        put(peer.domainName, broken)
        return broken
    }

    private fun put(key: String, value: LocateVerifyStatus) {
        _status.update { it + (key to value) }
    }

    private fun remove(key: String) {
        _status.update { it - key }
    }

    private fun staleTicker() = flow {
        while (true) {
            emit(Unit)
            delay(STALE_TICK_MS)
        }
    }

    private companion object {
        const val TAG = "EmergencyContactService"
    }
}
