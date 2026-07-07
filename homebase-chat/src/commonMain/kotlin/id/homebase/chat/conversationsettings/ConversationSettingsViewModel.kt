package id.homebase.chat.conversationsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.livelocation.LiveLocationReceiveStore
import id.homebase.chat.services.livelocation.LiveLocationShareService
import id.homebase.chat.services.livelocation.conversationLiveSharePinUntilMs
import id.homebase.core.ui.navigation.Route
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.measureTimedValue
import kotlin.uuid.Uuid

class ConversationSettingsViewModel(
    savedStateHandle: SavedStateHandle,
    val conversationService: ConversationService,
    private val conversationStream: ConversationStream,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val chatMessageStream: ChatMessageStream,
    private val contactService: ContactService,
    private val liveLocationShareService: LiveLocationShareService,
    private val liveLocationReceiveStore: LiveLocationReceiveStore,
) : ViewModel() {

    val route = savedStateHandle.toRoute<Route.ConversationSettings>()
    private val _uiState = MutableStateFlow(ConversationSettingsUiState())
    val uiState: StateFlow<ConversationSettingsUiState> = _uiState.asStateFlow()

    init {
        loadData()
        loadOverview()
        observeLiveShare()
    }


    fun onUiAction(action: ConversationSettingsUiAction) {
        when (action) {
            is ConversationSettingsUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = ConversationSettingsUiEvent.Back)}
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val conversationId = Uuid.parse(route.conversationId)
                if (conversationId == ChatProtocol.ConversationWithYourselfId) {
                    val conversation = conversationStream.getConversationById(conversationId)
                    val owner = ownerSessionRepository.user.value
                    _uiState.update {
                        it.copy(
                            conversation = conversation,
                            ownerSession = owner,
                            isLoading = false,
                        )
                    }
                } else {
                    val conversation = conversationService.getConversation(conversationId)
                    if (conversation != null) {
                        _uiState.update {
                            it.copy(
                                conversation = conversation,
                                contactName = resolveContactName(conversation),
                                groupsInCommon = groupsInCommonWith(conversation),
                                isLoading = false,
                            )
                        }
                    } else {
                        Logger.d("Failed to load contact for conversation")
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
            } catch (e: Exception) {
                Logger.e("Failed to load conversation", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Resolved full name of the 1:1 contact, or null to fall back to the raw
     * odinId in [ConversationUiModel.name]. The mapper deliberately stores the
     * odinId as the conversation name (contact resolution is a UI-layer concern,
     * see ConversationMapper), so we resolve it here against the contact list —
     * mirroring EnrichedConversationUiModel.getDisplayName for the list. Null for
     * groups, and when the contact carries no display name (resolves back to the
     * odinId), so the header doesn't render the same string twice.
     */
    private fun resolveContactName(conversation: ConversationUiModel): String? {
        if (conversation.isGroupConversation) return null
        val self = ownerSessionRepository.user.value?.odinId
        val contact = conversation.participants.firstOrNull { it != self } ?: return null
        val resolved = contactService.resolveByOdinId(contact).name
        return resolved.takeIf { it.isNotBlank() && it != contact.domainName }
    }

    /**
     * Group conversations that both the current identity and this 1:1's contact
     * belong to. The contact is the participant that isn't us; matching excludes
     * this conversation itself (which is 1:1, not a group, so it never matches).
     */
    private fun groupsInCommonWith(conversation: ConversationUiModel) =
        try {
            val self = ownerSessionRepository.user.value?.odinId
            val contact = conversation.participants.firstOrNull { it != self }
            if (contact == null) {
                persistentListOf()
            } else {
                conversationStream.conversations.value.items
                    .filter { it.isGroupConversation && it.participants.any { p -> p == contact } }
                    .map { GroupInCommonItem(it.id, it.name, it.avatarModel) }
                    .toImmutableList()
            }
        } catch (e: Exception) {
            Logger.e("Failed to compute groups in common", e)
            persistentListOf()
        }

    /**
     * Loads the media/files/audio/dice/location overview of this conversation.
     * Runs independently of [loadData] so the header paints before the (heavier)
     * message scan finishes.
     */
    private fun loadOverview() {
        val conversationId = Uuid.parse(route.conversationId)
        viewModelScope.launch {
            try {
                val batch = chatMessageStream.fetchMessages(
                    conversationId = conversationId,
                    limit = SUMMARY_MESSAGE_CAP,
                )
                val overview = withContext(Dispatchers.Default) {
                    collectConversationOverview(batch)
                }
                _uiState.update { it.copy(overview = overview, isOverviewLoading = false) }
            } catch (e: Exception) {
                Logger.e("Failed to load conversation overview", e)
                _uiState.update { it.copy(isOverviewLoading = false) }
            }
        }
    }

    /**
     * Drives this screen's live-share pin via [conversationLiveSharePinUntilMs] — the same
     * function the in-chat pin uses, so the two can never diverge (#1012): lit when I'm sharing
     * with anyone in this conversation (outgoing) OR the other party is sharing their live location
     * with me (incoming, quantized live-relay freshness), whichever ends later.
     *
     * The other participants are resolved through [ConversationStream.getRecipients] — again the
     * in-chat pin's seam — inheriting its empty-participants re-map fallback (#934).
     * [ConversationStream.conversations] is a combine source so the pin re-resolves once the
     * conversation row hydrates (otherwise an already-running outgoing share with no incoming stream
     * could leave the pin dark until the next roster/relay event). Note-to-self has no other party.
     */
    private fun observeLiveShare() {
        val conversationId = Uuid.parse(route.conversationId)
        if (conversationId == ChatProtocol.ConversationWithYourselfId) return
        viewModelScope.launch {
            combine(
                liveLocationShareService.recipients,
                liveLocationReceiveStore.positions,
                conversationStream.conversations,
            ) { roster, positions, _ -> roster to positions }
                .collectLatest { (roster, positions) ->
                    val now = Clock.System.now().toEpochMilliseconds()
                    // Re-resolves per relay packet during an incoming stream; slow-path log is the
                    // evidence gate for whether caching is ever needed (#1012 review).
                    val (others, took) = measureTimedValue {
                        runCatching {
                            conversationStream.getRecipients(conversationId, emptyList(), null)
                        }.getOrDefault(emptyList())
                    }
                    if (took.inWholeMilliseconds >= 5) {
                        Logger.i(tag = "LiveRelay") {
                            "settings pin getRecipients slow conv=$conversationId " +
                                "took=${took.inWholeMilliseconds}ms (re-runs per relay packet — cache if this recurs)"
                        }
                    }
                    _uiState.update {
                        it.copy(liveShareUntilMs = conversationLiveSharePinUntilMs(roster, positions, others, now))
                    }
                }
        }
    }

    companion object {
        /**
         * Upper bound on messages scanned for the conversation overview. Every
         * photo/video within these is surfaced in the media strip — the strip is
         * a LazyRow, so it only ever composes/loads the thumbnails on screen.
         */
        const val SUMMARY_MESSAGE_CAP = 1000
    }
}
