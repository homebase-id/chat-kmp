package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.util.initials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "MomentsRecipientLookupService"

/**
 * Aggregates the recipients available to the moments composer from chat
 * conversations and contacts and exposes them as a single MRU-ordered list.
 *
 * The public type ([MomentsRecipient]) is source-agnostic so the picker UI
 * (and any future selection surfaces) don't bind to chat-internal models.
 * Source provenance is retained internally as a stable key so MRU survives
 * across app restarts even though [MomentsRecipientId] is a fresh random
 * Uuid on every emission.
 */
class MomentsRecipientLookupService(
    private val contactService: ContactService,
    private val groupService: MomentGroupService,
    private val connectionService: ConnectionService,
    private val mruStore: MomentsUserStateStore,
    private val credentialsManager: CredentialsManager,
    private val scope: CoroutineScope,
) {

    private val _recipients = MutableStateFlow(MomentsRecipientsSnapshot.empty())
    val recipients: StateFlow<MomentsRecipientsSnapshot> = _recipients.asStateFlow()

    // Rebuilt on every emission. Used by recordUsed() to translate the
    // opaque per-emission MomentsRecipientId back to the persisted stable key.
    private var stableKeyById: Map<MomentsRecipientId, String> = emptyMap()

    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            combine(
                contactService.contacts,
                groupService.groups,
                connectionService.circles,
                mruStore.stableKeys,
            ) { contacts, groups, circleState, mru ->
                LookupInputs(contacts, groups, circleState.circles, mru)
            }.collect { inputs ->
                val activeUserDomain = credentialsManager.getActiveDomain()
                val (snapshot, keyMap) = buildRecipients(
                    inputs.contacts, inputs.groups, inputs.circles, inputs.mru, activeUserDomain,
                )
                stableKeyById = keyMap
                _recipients.value = snapshot
            }
        }
    }

    private data class LookupInputs(
        val contacts: List<ContactUiModel>,
        val groups: List<MomentGroup>,
        val circles: List<CircleWithMembers>,
        val mru: List<String>,
    )

    /**
     * Bumps [recipients] to the head of the MRU list and persists them so the
     * picker re-orders on the next emission. Call this from the composer
     * after a moment is successfully enqueued — typically with the full
     * selected-audience set, so a single read-modify-write cycle handles all
     * recipients (sequential per-recipient writes would be needlessly slow).
     *
     * Recipients no longer in the current snapshot (e.g. the source was
     * removed between emit and post) are silently skipped.
     *
     * **Fire-and-forget on the lookup service's scope.** Caller-controlled
     * scopes (e.g. the audience-picker `viewModelScope`) get cancelled the
     * moment the user navigates away after posting — which is faster than the
     * MRU upload completes and would silently drop the bump. We launch on
     * `scope` (the Koin singleton CoroutineScope) so the upload survives the
     * picker teardown and the optimistic local update + server write both
     * land. MRU writes are best-effort UX, not core functionality, so any
     * failure is logged and swallowed.
     */
    fun recordUsed(recipients: List<MomentsRecipient>) {
        val keys = recipients.mapNotNull { stableKeyById[it.id] }
        if (keys.isEmpty()) return
        scope.launch {
            try {
                mruStore.bump(keys)
            } catch (e: Exception) {
                Logger.w(throwable = e, tag = TAG) {
                    "recordUsed: MRU bump failed for ${keys.size} key(s) — non-fatal"
                }
            }
        }
    }

    private fun buildRecipients(
        contacts: List<ContactUiModel>,
        groups: List<MomentGroup>,
        circles: List<CircleWithMembers>,
        mru: List<String>,
        activeUserDomain: OdinId?,
    ): Pair<MomentsRecipientsSnapshot, Map<MomentsRecipientId, String>> {
        val contactsRaw = mutableListOf<Pair<MomentsRecipient, String>>()
        val groupsRaw = mutableListOf<Pair<MomentsRecipient, String>>()
        val circlesRaw = mutableListOf<Pair<MomentsRecipient, String>>()

        for (contact in contacts) {
            // Self-contact (if the drive ever lists the active user) is not a
            // valid moments recipient — you can't post a moment to yourself.
            if (activeUserDomain != null && contact.odinId == activeUserDomain) continue

            val recipient = MomentsRecipient.Individual(
                id = MomentsRecipientId(Uuid.random()),
                displayName = contact.name.ifBlank { contact.odinId.domainName },
                odinIds = listOf(contact.odinId),
                avatarInitials = contact.avatarInitials,
                avatarUrl = contact.avatarUrl,
            )
            contactsRaw += recipient to "contact:${contact.odinId.domainName}"
        }

        for (group in groups) {
            // Members may include self for "groups I'm in" semantics, but
            // transit recipients must not.
            val others = group.members.filterNot { it == activeUserDomain }
            if (others.isEmpty()) continue

            val recipient = MomentsRecipient.Group(
                id = MomentsRecipientId(Uuid.random()),
                displayName = group.title,
                odinIds = others,
                avatarInitials = group.title.initials(),
                avatarUrl = "",
                memberCount = others.size,
                groupId = group.id,
            )
            groupsRaw += recipient to "momentgroup:${group.id}"
        }

        for (cwm in circles) {
            val def = cwm.circle
            // User-defined circles only: drop the disabled ones and the Confirmed/Auto system
            // circles (Emergency and any other user circle stay). Mirrors the contact-detail
            // circle filter (#921/#916).
            if (def.disabled) continue
            if (def.id.equals(CONFIRMED_CONNECTIONS_CIRCLE_ID, ignoreCase = true) ||
                def.id.equals(AUTO_CONNECTIONS_CIRCLE_ID, ignoreCase = true)
            ) continue
            if (def.name.isBlank()) continue
            // Snapshot the members (v1 member expansion); self isn't a transit recipient.
            // Empty circles are kept (they render greyed-out + non-selectable in the picker so the
            // count is always visible) — the audience screen gates selection on odinIds.isNotEmpty().
            val others = cwm.members.filterNot { it == activeUserDomain }

            val recipient = MomentsRecipient.Circle(
                id = MomentsRecipientId(Uuid.random()),
                displayName = def.name,
                odinIds = others,
                avatarInitials = def.name.initials(),
                avatarUrl = "",
                memberCount = others.size,
                circleId = def.id,
            )
            circlesRaw += recipient to "circle:${def.id.lowercase()}"
        }

        val mruIndex = mru.withIndex().associate { (i, k) -> k to i }
        fun isMru(p: Pair<MomentsRecipient, String>) = mruIndex.containsKey(p.second)

        val recentRaw = (contactsRaw + groupsRaw + circlesRaw).filter { isMru(it) }
        val recentSorted = recentRaw.sortedBy { mruIndex.getValue(it.second) }

        val nonRecentGroups = groupsRaw
            .filterNot { isMru(it) }
            .sortedBy { it.first.displayName.lowercase() }
        val nonRecentCircles = circlesRaw
            .filterNot { isMru(it) }
            .sortedBy { it.first.displayName.lowercase() }
        val nonRecentContacts = contactsRaw
            .filterNot { isMru(it) }
            .sortedBy { it.first.displayName.lowercase() }

        val snapshot = MomentsRecipientsSnapshot(
            recent = recentSorted.map { it.first },
            groups = nonRecentGroups.map { it.first },
            circles = nonRecentCircles.map { it.first },
            contacts = nonRecentContacts.map { it.first },
        )
        val keyMap = (recentSorted + nonRecentGroups + nonRecentCircles + nonRecentContacts)
            .associate { (recipient, key) -> recipient.id to key }
        return snapshot to keyMap
    }
}

/**
 * Recipient list snapshot for the moments composer's audience picker.
 *
 * @property recent    MRU-bumped recipients in MRU order (most recent first).
 *                     May contain entries from either source.
 * @property groups    Moments groups (defined via `MomentGroupService`) NOT in
 *                     [recent], sorted alphabetically by title.
 * @property circles   User-defined circles (system circles excluded) NOT in
 *                     [recent], sorted alphabetically by name.
 * @property contacts  Address-book contacts NOT in [recent], sorted
 *                     alphabetically by displayName.
 */
data class MomentsRecipientsSnapshot(
    val recent: List<MomentsRecipient>,
    val groups: List<MomentsRecipient>,
    val circles: List<MomentsRecipient>,
    val contacts: List<MomentsRecipient>,
) {
    /** Flat MRU-first view — recent + groups + circles + contacts. */
    val all: List<MomentsRecipient> get() = recent + groups + circles + contacts

    companion object {
        fun empty(): MomentsRecipientsSnapshot = MomentsRecipientsSnapshot(
            recent = emptyList(),
            groups = emptyList(),
            circles = emptyList(),
            contacts = emptyList(),
        )
    }
}
