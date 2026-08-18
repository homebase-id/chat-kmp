package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.query.TimeRowCursor
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.MessageUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
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
    /** Windowed sync (#1223): the server may hold messages older than local EOS. */
    val serverHasMoreOlder: Boolean = false,
    val isLoadingOlderFromServer: Boolean = false,
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

    /**
     * Conversations whose initial load is in flight, each mapped to whether a
     * concurrent write landed while that load ran.
     *
     * A conversation has no window until [setInitialWindow], so a DriveSync/WS
     * write committing mid-fetch is invisible to the fetch's SQLite snapshot
     * *and* skipped by both reconcilers — the stale window is then cached
     * forever (#1135). Registering the load here gives those reconcilers
     * something to flag; the loader re-reads once when it sees the flag.
     *
     * Same `MutableStateFlow` CAS discipline as [_windows] so the flag is safe
     * against reconcilers running on other dispatchers.
     */
    private val _initialLoads = MutableStateFlow<Map<Uuid, Boolean>>(emptyMap())

    /**
     * Drop every cached message window. Called on logout so the previous
     * identity's open-conversation messages don't survive in memory into the
     * next session; windows reload lazily via [setInitialWindow] when the user
     * reopens a conversation.
     */
    fun reset() {
        _windows.value = emptyMap()
        _initialLoads.value = emptyMap()
    }

    /** Register [conversationId] as mid-initial-load. Pair with [endInitialLoad]. */
    fun beginInitialLoad(conversationId: Uuid) {
        _initialLoads.update { it + (conversationId to false) }
    }

    /**
     * Record that a write landed for [conversationId] while its initial load is
     * in flight. No-op when no load is in flight.
     */
    fun markInitialLoadDirty(conversationId: Uuid) {
        _initialLoads.update { current ->
            if (current.containsKey(conversationId)) current + (conversationId to true) else current
        }
    }

    /**
     * Flag every in-flight initial load. Used by the whole-drive post-sync
     * refresh, which walks [windows] and so cannot see a conversation that
     * doesn't have one yet.
     */
    fun markAllInitialLoadsDirty() {
        _initialLoads.update { current ->
            if (current.isEmpty()) current else current.mapValues { true }
        }
    }

    /**
     * Clear [conversationId]'s in-flight marker, returning true when a write
     * landed while it was set — i.e. the caller's fetch may have missed rows
     * and must re-read.
     */
    fun endInitialLoad(conversationId: Uuid): Boolean =
        _initialLoads.getAndUpdate { it - conversationId }[conversationId] == true

    fun hasCachedMessages(conversationId: Uuid): Boolean =
        _windows.value.containsKey(conversationId)

    fun getWindow(conversationId: Uuid): MessageWindow? =
        _windows.value[conversationId]

    /**
     * @param merge keep any message the existing window holds that [messages]
     *  doesn't ([messages] wins on id conflicts), instead of replacing it
     *  wholesale. Set ONLY by the raced-load re-read: that second fetch takes
     *  its own SQLite snapshot, so a write upserted into the (by then existing)
     *  window while it ran would otherwise be replaced away and lost for good —
     *  the exact #1135 failure, just one fetch later. Cursors always come from
     *  [messages]: the survivors sit at the newest end, so the fresh page's
     *  older-side cursor stays valid and any over-fetch is deduped by
     *  [prependOlderMessages].
     *
     *  The union must happen inside this single atomic update — a caller-side
     *  read-then-write would race exactly the same way.
     */
    fun setInitialWindow(
        conversationId: Uuid,
        messages: List<MessageUiModel>,
        olderCursor: QueryBatchCursor? = null,
        hasOlderMessages: Boolean = false,
        newerCursor: QueryBatchCursor? = null,
        hasNewerMessages: Boolean = false,
        serverHasMoreOlder: Boolean = false,
        merge: Boolean = false,
    ) {
        _windows.update { current ->
            val existing = if (merge) current[conversationId]?.messages.orEmpty() else emptyList()
            val combined = if (existing.isEmpty()) {
                messages
            } else {
                val byId = LinkedHashMap<Uuid, MessageUiModel>()
                for (msg in existing) byId[msg.id] = msg
                for (msg in messages) byId[msg.id] = msg
                byId.values.toList()
            }
            current + (conversationId to MessageWindow(
                messages = combined.sortedBy { it.userDate },
                olderCursor = olderCursor,
                newerCursor = newerCursor,
                hasOlderMessages = hasOlderMessages,
                hasNewerMessages = hasNewerMessages,
                serverHasMoreOlder = serverHasMoreOlder,
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
                isLoadingOlderFromServer = false,
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

    fun setLoadingOlderFromServer(conversationId: Uuid, loading: Boolean) {
        _windows.update { current ->
            val existing = current[conversationId] ?: return@update current
            current + (conversationId to existing.copy(isLoadingOlderFromServer = loading))
        }
    }

    fun setServerHasMoreOlder(conversationId: Uuid, value: Boolean) {
        _windows.update { current ->
            val existing = current[conversationId] ?: return@update current
            current + (conversationId to existing.copy(serverHasMoreOlder = value))
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
