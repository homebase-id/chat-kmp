package id.homebase.chat.services.convo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.uuid.Uuid

/**
 * One-shot signal channel between the group-creation flow and the conversation-list
 * post-create dialog. After [id.homebase.chat.createconversationgroup.CreateConversationGroupViewModel]
 * successfully creates a group, it emits an [Event] here. [id.homebase.chat.conversationlist.ConversationListViewModel]
 * collects the flow and runs an introduction-preflight check post-creation; if any
 * recipient is non-Ready it surfaces the existing
 * [id.homebase.chat.conversationlist.ConversationListUiDialog.IntroducePreflight]
 * dialog so the user can choose to re-send introductions, send only to the Ready
 * subset, or dismiss.
 *
 * Why a separate bus rather than e.g. piggy-backing on `pendingConversationId`:
 * we need a payload (the default introduction message) and we want the signal to
 * be explicit — `pendingConversationId` is set every time the user creates ANY
 * conversation (1:1 forwards too), and most of those don't warrant a preflight.
 * This bus is only emitted to from the group-creation path.
 *
 * Buffer capacity is generous — the producer can fire-and-forget, and any
 * collector subscribed at session-start is guaranteed not to miss recent events.
 */
class PostCreateIntroductionPreflightBus {
    /** Backing flow. `extraBufferCapacity = 16` so a producer never suspends; the
     *  consumer (ConversationListViewModel) starts collecting in its init block
     *  long before any group is created in a session. */
    private val _events = MutableSharedFlow<Uuid>(extraBufferCapacity = 16)

    val events: SharedFlow<Uuid> = _events.asSharedFlow()

    /** Emitted by [id.homebase.chat.createconversationgroup.CreateConversationGroupViewModel]
     *  after a successful group create. The collector is responsible for building
     *  the introduction message (it has access to ownerSession state). */
    suspend fun emit(conversationId: Uuid) {
        _events.emit(conversationId)
    }
}
