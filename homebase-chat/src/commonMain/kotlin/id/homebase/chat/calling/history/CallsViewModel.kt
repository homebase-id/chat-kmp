package id.homebase.chat.calling.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.history.CallHistoryEntry
import id.homebase.chat.services.calling.history.CallHistorySource
import id.homebase.chat.services.convo.ConversationParticipantLookup
import id.homebase.chat.services.convo.contact.ContactConnectionState
import id.homebase.core.avatars.ConversationAvatarModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun interface CallLogDeleter {
    suspend fun delete(messageId: Uuid, deleteForEveryone: Boolean)
}

sealed interface CallDay {
    data object Today : CallDay
    data object Yesterday : CallDay
    data class On(val date: LocalDate) : CallDay
}

data class CallRowUi(
    val entry: CallHistoryEntry,
    val displayName: String,
    val avatarInitials: String,
    val avatarModel: ConversationAvatarModel?,
    val canCall: Boolean,
)

data class CallSectionUi(val day: CallDay, val rows: List<CallRowUi>)

data class CallsUiState(
    val sections: List<CallSectionUi> = emptyList(),
    val isLoading: Boolean = true,
    val hasMore: Boolean = false,
) {
    val isEmpty: Boolean get() = sections.isEmpty() && !isLoading
}

sealed interface CallsAction {
    data class CallBack(val entry: CallHistoryEntry, val video: Boolean) : CallsAction
    data class OpenChat(val conversationId: Uuid, val messageId: Uuid? = null) : CallsAction
    data class Delete(val messageId: Uuid) : CallsAction
    data object TabOpened : CallsAction
    data object LoadMore : CallsAction
}

sealed interface CallsEvent {
    data class StartCall(val peer: OdinId, val video: Boolean) : CallsEvent
    data class OpenConversation(val conversationId: Uuid, val messageId: Uuid? = null) : CallsEvent
    data class DeleteFailed(val reason: String?) : CallsEvent
}

class CallsViewModel(
    private val history: CallHistorySource,
    contacts: Flow<List<ContactUiModel>>,
    private val conversations: ConversationParticipantLookup,
    private val session: CallSession,
    private val callsAvailable: Boolean,
    private val deleter: CallLogDeleter,
    private val clock: () -> Instant = { Clock.System.now() },
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(CallsUiState())
    val uiState: StateFlow<CallsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CallsEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<CallsEvent> = _events.asSharedFlow()

    val missedCount: StateFlow<Int> = history.missedCount

    private var contactsByOdinId: Map<OdinId, ContactUiModel> = emptyMap()
    private var callActive = false

    init {
        viewModelScope.launch {
            combine(
                history.entries,
                history.hasMore,
                history.isLoading,
                contacts,
                session.state.map { it.isActive }.distinctUntilChanged(),
            ) { entries, hasMore, loading, contactList, active ->
                contactsByOdinId = contactList.associateBy { it.odinId }
                callActive = active
                CallsUiState(
                    sections = sections(entries),
                    isLoading = loading,
                    hasMore = hasMore,
                )
            }.collect { state -> _uiState.update { state } }
        }
        launchLogged { history.refresh() }
    }

    fun onAction(action: CallsAction) {
        when (action) {
            is CallsAction.CallBack -> if (canCall(action.entry.peer)) {
                _events.tryEmit(CallsEvent.StartCall(action.entry.peer, action.video))
            }
            is CallsAction.OpenChat -> _events.tryEmit(CallsEvent.OpenConversation(action.conversationId, action.messageId))
            is CallsAction.Delete -> viewModelScope.launch {
                try {
                    deleter.delete(action.messageId, deleteForEveryone = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Logger.w(t, tag = "CallsViewModel") { "Delete call failed" }
                    _events.tryEmit(CallsEvent.DeleteFailed(t.message))
                }
            }
            CallsAction.TabOpened -> launchLogged { history.markSeen() }
            CallsAction.LoadMore -> launchLogged { history.loadMore() }
        }
    }

    private fun launchLogged(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.w(t, tag = "CallsViewModel") { "Calls action failed" }
            }
        }
    }

    private fun canCall(peer: OdinId): Boolean =
        callsAvailable && !callActive &&
            contactsByOdinId[peer]?.connectionState == ContactConnectionState.Connected

    private fun sections(entries: List<CallHistoryEntry>): List<CallSectionUi> {
        val tz = timeZone()
        val today = clock().toLocalDateTime(tz).date
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        return entries
            .groupBy { it.at.toLocalDateTime(tz).date }
            .map { (date, group) ->
                val day = when (date) {
                    today -> CallDay.Today
                    yesterday -> CallDay.Yesterday
                    else -> CallDay.On(date)
                }
                CallSectionUi(day, group.map(::row))
            }
    }

    private fun row(entry: CallHistoryEntry): CallRowUi {
        val contact = contactsByOdinId[entry.peer] ?: ContactUiModel.fallbackFor(entry.peer)
        return CallRowUi(
            entry = entry,
            displayName = contact.name,
            avatarInitials = contact.avatarInitials,
            avatarModel = conversations.getConversationById(entry.conversationId)?.avatarModel,
            canCall = canCall(entry.peer),
        )
    }
}
