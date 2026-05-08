package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.contact.ContactService
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
    private val conversationStream: ConversationStream,
    private val mruStore: MomentsRecipientMruStore,
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
                conversationStream.conversations,
                mruStore.stableKeys,
            ) { contacts, conversationsData, mru ->
                Triple(contacts, conversationsData.items, mru)
            }.collect { (contacts, conversations, mru) ->
                val activeUserDomain = credentialsManager.getActiveDomain()
                val (snapshot, keyMap) =
                    buildRecipients(contacts, conversations, mru, activeUserDomain)
                stableKeyById = keyMap
                _recipients.value = snapshot
            }
        }
    }

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
        conversations: List<ConversationUiModel>,
        mru: List<String>,
        activeUserDomain: OdinId?,
    ): Pair<MomentsRecipientsSnapshot, Map<MomentsRecipientId, String>> {
        // Build two parallel raw lists, one per source. The same person can
        // legitimately appear in BOTH (a contact-card entry AND a 1:1 chat
        // conversation): they're different concepts — "address book" vs
        // "active conversation thread" — so the picker surfaces them in
        // different sections rather than deduping. Posting routes through
        // `odinIds` either way, so the resulting send is functionally
        // identical.
        val contactsRaw = mutableListOf<Pair<MomentsRecipient, String>>()
        val conversationsRaw = mutableListOf<Pair<MomentsRecipient, String>>()

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

        for (convo in conversations) {
            if (convo.isWithSelf) continue
            if (convo.conversationState == ConversationState.Left) continue
            if (convo.conversationState == ConversationState.Removed) continue
            if (convo.conversationState == ConversationState.RejoinPending) continue
            if (convo.conversationState == ConversationState.Invalid) continue

            val others = convo.participants.filterNot { it == activeUserDomain }
            if (others.isEmpty()) continue

            val recipient = if (convo.isGroupConversation) {
                MomentsRecipient.Group(
                    id = MomentsRecipientId(Uuid.random()),
                    displayName = convo.getDisplayName(),
                    odinIds = others,
                    avatarInitials = convo.avatarInitials,
                    avatarUrl = convo.avatarUrl,
                    memberCount = others.size,
                )
            } else {
                MomentsRecipient.Individual(
                    id = MomentsRecipientId(Uuid.random()),
                    displayName = convo.getDisplayName(),
                    odinIds = others,
                    avatarInitials = convo.avatarInitials,
                    avatarUrl = convo.avatarUrl,
                )
            }
            conversationsRaw += recipient to "conversation:${convo.id}"
        }

        // Partition each source into recent (MRU-bumped) vs the rest. Recent
        // items get pulled into a single mixed-source `recent` section so
        // they sit at the top regardless of provenance. Conversations and
        // contacts then list their non-recent rows alphabetically.
        val mruIndex = mru.withIndex().associate { (i, k) -> k to i }
        fun isMru(p: Pair<MomentsRecipient, String>) = mruIndex.containsKey(p.second)

        val recentRaw = (contactsRaw + conversationsRaw).filter { isMru(it) }
        val recentSorted = recentRaw.sortedBy { mruIndex.getValue(it.second) }

        val nonRecentConversations = conversationsRaw
            .filterNot { isMru(it) }
            .sortedBy { it.first.displayName.lowercase() }
        val nonRecentContacts = contactsRaw
            .filterNot { isMru(it) }
            .sortedBy { it.first.displayName.lowercase() }

        val snapshot = MomentsRecipientsSnapshot(
            recent = recentSorted.map { it.first },
            conversations = nonRecentConversations.map { it.first },
            contacts = nonRecentContacts.map { it.first },
        )
        val keyMap = (recentSorted + nonRecentConversations + nonRecentContacts)
            .associate { (recipient, key) -> recipient.id to key }
        return snapshot to keyMap
    }
}

/**
 * Recipient list snapshot for the moments composer's audience picker.
 *
 * Sources are kept separate (`conversations` vs `contacts`) instead of
 * partitioning by recipient TYPE (Group vs Individual): a person can
 * legitimately appear in both an active 1:1 chat AND the contact list, and
 * those represent different concepts to the user — the conversation row
 * means "the chat thread I have with them," the contact row means "this
 * person from my address book." Posting resolves to the same `odinIds`
 * either way, so the choice is purely UX framing.
 *
 * @property recent         MRU-bumped recipients in MRU order (most recent
 *                          first). May contain entries from either source.
 *                          Empty for fresh users who have never posted.
 * @property conversations  Chat conversations (groups + 1:1) NOT in [recent],
 *                          sorted alphabetically by displayName.
 * @property contacts       Address-book contacts NOT in [recent], sorted
 *                          alphabetically by displayName.
 */
data class MomentsRecipientsSnapshot(
    val recent: List<MomentsRecipient>,
    val conversations: List<MomentsRecipient>,
    val contacts: List<MomentsRecipient>,
) {
    /** Flat MRU-first view — recent + conversations + contacts. */
    val all: List<MomentsRecipient> get() = recent + conversations + contacts

    companion object {
        fun empty(): MomentsRecipientsSnapshot = MomentsRecipientsSnapshot(
            recent = emptyList(),
            conversations = emptyList(),
            contacts = emptyList(),
        )
    }
}
