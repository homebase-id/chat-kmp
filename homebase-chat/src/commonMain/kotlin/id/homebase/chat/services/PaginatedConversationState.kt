package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.query.TimeRowCursor
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.MessageUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/**
 * Snapshot of an open conversation's locally-loaded message slice.
 *
 * The window is held in **ascending userDate** order (oldest first, newest
 * last). The VM re-sorts before grouping/clustering, so consumers may read
 * the list in any order — but trim/append/prepend logic below relies on
 * this invariant.
 */
data class MessageWindow(
    val messages: List<MessageUiModel> = emptyList(),
    val olderCursor: QueryBatchCursor? = null,
    val newerCursor: QueryBatchCursor? = null,
    val hasOlderMessages: Boolean = false,
    val hasNewerMessages: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isLoadingNewer: Boolean = false,
)

/**
 * Holds the [MessageWindow] for each open conversation. Replaces
 * `ActiveConversationState` — same lifecycle (cleared on conversation
 * eviction, mutated by stream events), with explicit support for
 * bidirectional cursor-driven paging.
 *
 * PR-A introduces this holder but does not yet drive paging from the
 * UI; callers still load the full 1000-message slice and store it as a
 * single window with `hasOlderMessages = false` / `hasNewerMessages = false`.
 * PR-B turns paging on by lowering the initial fetch to [PAGE_SIZE].
 *
 * @param maxWindowSize override for [MAX_WINDOW_SIZE]; tests use a smaller
 *  value so trim/recovery paths can be exercised without seeding hundreds
 *  of rows. Production never overrides this.
 */
class PaginatedConversationState(
    private val maxWindowSize: Int = MAX_WINDOW_SIZE,
) {

    companion object {
        const val PAGE_SIZE = 75
        const val MAX_WINDOW_SIZE = 300
        const val LOAD_MORE_THRESHOLD = 15
    }

    private val _windows = MutableStateFlow<Map<Uuid, MessageWindow>>(emptyMap())
    val windows: StateFlow<Map<Uuid, MessageWindow>> = _windows.asStateFlow()

    fun hasCachedMessages(conversationId: Uuid): Boolean =
        _windows.value.containsKey(conversationId)

    fun getWindow(conversationId: Uuid): MessageWindow? =
        _windows.value[conversationId]

    fun setInitialWindow(
        conversationId: Uuid,
        messages: List<MessageUiModel>,
        olderCursor: QueryBatchCursor? = null,
        hasOlderMessages: Boolean = false,
        newerCursor: QueryBatchCursor? = null,
        hasNewerMessages: Boolean = false,
    ) {
        _windows.update { current ->
            current + (conversationId to MessageWindow(
                messages = messages.sortedBy { it.userDate },
                olderCursor = olderCursor,
                newerCursor = newerCursor,
                hasOlderMessages = hasOlderMessages,
                hasNewerMessages = hasNewerMessages,
            ))
        }
    }

    fun prependOlderMessages(
        conversationId: Uuid,
        olderMessages: List<MessageUiModel>,
        olderCursor: QueryBatchCursor?,
        hasMore: Boolean,
    ) {
        _windows.update { current ->
            val existing = current[conversationId] ?: return@update current
            // Existing-wins on duplicate id: the in-memory window may carry
            // upserts (versionTag bumps from stream events) that the freshly
            // fetched older page doesn't reflect.
            val combined = (existing.messages + olderMessages)
                .distinctBy { it.id }
                .sortedBy { it.userDate }

            val (trimmed, newerCursor, hasNewer) = trimTail(
                combined, existing.newerCursor, existing.hasNewerMessages
            )

            current + (conversationId to existing.copy(
                messages = trimmed,
                olderCursor = olderCursor,
                hasOlderMessages = hasMore,
                newerCursor = newerCursor,
                hasNewerMessages = hasNewer,
                isLoadingOlder = false,
            ))
        }
    }

    fun appendNewerMessages(
        conversationId: Uuid,
        newerMessages: List<MessageUiModel>,
        newerCursor: QueryBatchCursor?,
        hasMore: Boolean,
    ) {
        _windows.update { current ->
            val existing = current[conversationId] ?: return@update current
            val combined = (existing.messages + newerMessages)
                .distinctBy { it.id }
                .sortedBy { it.userDate }

            val (trimmed, olderCursor, hasOlder) = trimHead(
                combined, existing.olderCursor, existing.hasOlderMessages
            )

            current + (conversationId to existing.copy(
                messages = trimmed,
                olderCursor = olderCursor,
                hasOlderMessages = hasOlder,
                newerCursor = newerCursor,
                hasNewerMessages = hasMore,
                isLoadingNewer = false,
            ))
        }
    }

    /**
     * Insert or update messages from a real-time stream event. Only mutates
     * windows that already exist (i.e. open conversations). Caller must
     * gate this with `!window.hasNewerMessages` — when the user is deep in
     * history, brand-new messages live outside the loaded window and would
     * appear out of order if upserted blindly.
     */
    fun upsert(conversationId: Uuid, incoming: List<MessageUiModel>) {
        if (incoming.isEmpty()) return
        _windows.update { current ->
            val existing = current[conversationId] ?: return@update current
            val byId = existing.messages.associateBy { it.id }.toMutableMap()
            for (msg in incoming) {
                byId[msg.id] = msg
            }
            current + (conversationId to existing.copy(
                messages = byId.values.sortedBy { it.userDate }
            ))
        }
    }

    fun removeMessage(messageId: Uuid) {
        _windows.update { current ->
            current.mapValues { (_, window) ->
                window.copy(messages = window.messages.filter { it.id != messageId })
            }
        }
    }

    fun removeByFileId(conversationId: Uuid, fileId: Uuid) {
        _windows.update { current ->
            val existing = current[conversationId] ?: return@update current
            current + (conversationId to existing.copy(
                messages = existing.messages.filter { it.fileId != fileId }
            ))
        }
    }

    fun setLoadingOlder(conversationId: Uuid, loading: Boolean) {
        _windows.update { current ->
            val existing = current[conversationId] ?: return@update current
            current + (conversationId to existing.copy(isLoadingOlder = loading))
        }
    }

    fun setLoadingNewer(conversationId: Uuid, loading: Boolean) {
        _windows.update { current ->
            val existing = current[conversationId] ?: return@update current
            current + (conversationId to existing.copy(isLoadingNewer = loading))
        }
    }

    /**
     * Drop messages from the tail (newest end, ASCending order = end of list)
     * when the list exceeds [MAX_WINDOW_SIZE]. The new last message becomes
     * the boundary; build a [QueryBatchCursor] anchored just past it so a
     * subsequent `loadNewerMessages` (OldestFirst) re-covers the discarded
     * slice including any tied-userDate rows.
     *
     * `rowId = 0L` with OldestFirst's strict `>` operator captures rows
     * with strictly greater userDate AND tied-userDate rows whose rowId > 0
     * (which is every real row). The boundary message itself reappears in
     * the next fetch but is filtered out by `distinctBy { it.id }` in
     * [appendNewerMessages].
     */
    private fun trimTail(
        messages: List<MessageUiModel>,
        currentNewerCursor: QueryBatchCursor?,
        currentHasNewer: Boolean,
    ): Triple<List<MessageUiModel>, QueryBatchCursor?, Boolean> {
        if (messages.size <= maxWindowSize) {
            return Triple(messages, currentNewerCursor, currentHasNewer)
        }
        val trimmed = messages.take(maxWindowSize)
        val boundary = trimmed.last()
        val newerCursor = QueryBatchCursor(
            paging = TimeRowCursor(UnixTimeUtc(boundary.userDate), 0L)
        )
        Logger.d(tag = "ChatPaging") {
            "trimTail dropped=${messages.size - trimmed.size} kept=${trimmed.size} " +
                "boundaryUserDate=${boundary.userDate} → newer cursor recovered (hasNewer=true)"
        }
        return Triple(trimmed, newerCursor, true)
    }

    /**
     * Drop messages from the head (oldest end, ASCending order = start of
     * list) when the list exceeds [MAX_WINDOW_SIZE]. The new first message
     * becomes the boundary; build a cursor for a subsequent
     * `loadOlderMessages` (NewestFirst).
     *
     * `rowId = Long.MAX_VALUE` with NewestFirst's strict `<` operator
     * captures rows with strictly lesser userDate AND tied-userDate rows
     * whose rowId < MAX (every real row). The boundary message itself
     * reappears but is dedup'd in [prependOlderMessages].
     */
    private fun trimHead(
        messages: List<MessageUiModel>,
        currentOlderCursor: QueryBatchCursor?,
        currentHasOlder: Boolean,
    ): Triple<List<MessageUiModel>, QueryBatchCursor?, Boolean> {
        if (messages.size <= maxWindowSize) {
            return Triple(messages, currentOlderCursor, currentHasOlder)
        }
        val trimmed = messages.takeLast(maxWindowSize)
        val boundary = trimmed.first()
        val olderCursor = QueryBatchCursor(
            paging = TimeRowCursor(UnixTimeUtc(boundary.userDate), Long.MAX_VALUE)
        )
        Logger.d(tag = "ChatPaging") {
            "trimHead dropped=${messages.size - trimmed.size} kept=${trimmed.size} " +
                "boundaryUserDate=${boundary.userDate} → older cursor recovered (hasOlder=true)"
        }
        return Triple(trimmed, olderCursor, true)
    }
}
