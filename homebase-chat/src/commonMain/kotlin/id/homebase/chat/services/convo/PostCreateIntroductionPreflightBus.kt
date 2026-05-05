package id.homebase.chat.services.convo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * Signal channel between the group-creation flow and the conversation-list
 * post-create dialog. After [id.homebase.chat.createconversationgroup.CreateConversationGroupViewModel]
 * successfully creates a group, it emits the new conversationId here.
 * [id.homebase.chat.conversationlist.ConversationListViewModel] collects, runs an
 * introduction-preflight check, and surfaces the
 * [id.homebase.chat.conversationlist.ConversationListUiDialog.IntroducePreflight]
 * dialog if any recipient is non-Ready.
 *
 * **Why a `StateFlow` and not a `SharedFlow`:** on mobile the conversation-list
 * VM doesn't exist while the user is on the create-group screen, so the bus is
 * emitted to with no subscriber. A `MutableSharedFlow` with default `replay=0`
 * silently drops the event in that window. A `StateFlow` always replays the
 * latest value to new subscribers, so the consumer sees the pending id the
 * moment it starts collecting — regardless of when (or if) it was alive when
 * the producer emitted. The trade-off: rapid back-to-back creates collapse to
 * the latest id (acceptable for this UX — users create one group at a time).
 */
class PostCreateIntroductionPreflightBus {
    private val _pending = MutableStateFlow<Uuid?>(null)

    /** Latest conversationId awaiting post-create preflight, or null if there is
     *  none / it has been consumed. Collectors should `filterNotNull()` then
     *  call [consume] after they've handled the value. */
    val pending: StateFlow<Uuid?> = _pending.asStateFlow()

    /** Called by the producer after a successful group create. Overwrites any
     *  previous unconsumed value — see class kdoc on the rapid-back-to-back
     *  trade-off. */
    fun emit(conversationId: Uuid) {
        _pending.value = conversationId
    }

    /** Called by the consumer after handling a value. Compare-and-set so a
     *  newly-emitted id (from a second create that happened mid-handle) is
     *  not accidentally cleared. */
    fun consume(conversationId: Uuid) {
        _pending.compareAndSet(conversationId, null)
    }
}
