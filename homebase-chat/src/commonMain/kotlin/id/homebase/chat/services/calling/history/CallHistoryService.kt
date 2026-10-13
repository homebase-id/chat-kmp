package id.homebase.chat.services.calling.history

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.calling.CallEventDescriptor
import id.homebase.chat.calling.CallLogDirection
import id.homebase.chat.calling.CallOutcome
import id.homebase.chat.calling.callEventVisual
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.convo.ConversationParticipantLookup
import id.homebase.chat.services.mapToMessageData
import id.homebase.core.calling.CallsPreferences
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class CallHistoryEntry(
    val messageId: Uuid,
    val fileId: Uuid,
    val conversationId: Uuid,
    val peer: OdinId,
    val direction: CallLogDirection,
    val outcome: CallOutcome,
    val missed: Boolean,
    val video: Boolean,
    val durationSeconds: Long,
    val at: Instant,
)

interface CallHistorySource {
    val entries: StateFlow<List<CallHistoryEntry>>
    val hasMore: StateFlow<Boolean>
    val isLoading: StateFlow<Boolean>
    suspend fun refresh()
    suspend fun loadMore()
    suspend fun markSeen()
}

/** Newest-first call log derived from the CallEvent chat messages; no storage of its own. */
class CallHistoryService(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    eventBus: EventBus,
    private val conversations: ConversationParticipantLookup,
    private val preferences: CallsPreferences,
    private val scope: CoroutineScope,
    conversationsReady: Flow<Boolean> = emptyFlow(),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val pageSize: Int = PAGE_SIZE,
) : CallHistorySource {
    private val chatDrive = chatTargetDrive.alias
    private val lock = Mutex()
    private val byMessageId = HashMap<Uuid, CallHistoryEntry>()
    private val unparseableLogged = HashSet<Uuid>()
    private var cursor: QueryBatchCursor? = null

    private val _entries = MutableStateFlow<List<CallHistoryEntry>>(emptyList())
    override val entries: StateFlow<List<CallHistoryEntry>> = _entries.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    override val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val missedCount: StateFlow<Int> = combine(entries, preferences.lastSeenMissedAtMs) { list, seen ->
        list.count { it.missed && it.at.toEpochMilliseconds() > seen }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is BackendEvent.SessionEnded -> lock.withLock {
                        byMessageId.clear()
                        unparseableLogged.clear()
                        cursor = null
                        _hasMore.value = false
                        publish()
                    }
                    is BackendEvent.OutboxEvent.OptimisticRollback -> if (event.driveId == chatDrive) {
                        lock.withLock {
                            if (byMessageId.remove(event.uniqueId) != null) publish()
                        }
                    }
                    is BackendEvent.DataEvent.BatchReceived -> if (event.driveId == chatDrive) {
                        onBatch(event.batchData)
                    }
                    else -> {}
                }
            }
        }
        scope.launch {
            conversationsReady.distinctUntilChanged().filter { it }.collect { runCatchingNonCancel { refresh() } }
        }
    }

    override suspend fun refresh() {
        lock.withLock {
            _isLoading.value = true
            try {
                val page = fetchPage(null)
                byMessageId.clear()
                page.entries.forEach { byMessageId[it.messageId] = it }
                cursor = page.cursor
                _hasMore.value = page.hasMore
                publish()
            } finally {
                _isLoading.value = false
            }
        }
    }

    override suspend fun loadMore() {
        lock.withLock {
            val from = cursor
            if (!_hasMore.value || from == null) return
            _isLoading.value = true
            try {
                val page = fetchPage(from)
                page.entries.forEach { byMessageId[it.messageId] = it }
                cursor = page.cursor
                _hasMore.value = page.hasMore
                publish()
            } finally {
                _isLoading.value = false
            }
        }
    }

    override suspend fun markSeen() {
        val newestMissed = entries.value.filter { it.missed }.maxOfOrNull { it.at.toEpochMilliseconds() } ?: 0L
        preferences.setLastSeenMissedAtMs(maxOf(nowMs(), newestMissed))
    }

    private class Page(val entries: List<CallHistoryEntry>, val cursor: QueryBatchCursor, val hasMore: Boolean)

    private suspend fun fetchPage(from: QueryBatchCursor?): Page {
        val c = credentialsManager.requireActiveCredentials()
        val result = QueryBatch(c.getIdentityId()).queryBatchAsync(
            dbm = dbm,
            driveId = chatDrive,
            noOfItems = pageSize,
            cursor = from,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            fileState = FileStateFilter.Active,
            filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
            datatypesAnyOf = listOf(ChatProtocol.ChatCallEventMessageDataType),
            groupIdAnyOf = null,
        )
        val mapped = withContext(Dispatchers.Default) {
            result.records.mapNotNull { toEntry(it) }
        }
        return Page(mapped, result.cursor, result.hasMoreRows)
    }

    private suspend fun onBatch(files: List<HomebaseFile>) {
        val relevant = files.filter {
            it.fileMetadata.appData.fileType == ChatProtocol.MessageFileType &&
                it.fileMetadata.appData.dataType == ChatProtocol.ChatCallEventMessageDataType
        }
        if (relevant.isEmpty()) return
        lock.withLock {
            var changed = false
            for (file in relevant) {
                val msg = withContext(Dispatchers.Default) { mapToMessageData(file, credentialsManager) } ?: continue
                if (msg.isDeleted) {
                    changed = (byMessageId.remove(msg.id) != null) or (byMessageId.remove(msg.fileId) != null) or changed
                    continue
                }
                val entry = toEntry(msg) ?: continue
                // An echo keyed by fileId replaces the optimistic row keyed by the temp id.
                if (msg.id == msg.fileId) byMessageId.values.removeAll { it.fileId == msg.fileId }
                byMessageId[entry.messageId] = entry
                changed = true
            }
            if (changed) publish()
        }
    }

    private suspend fun toEntry(file: HomebaseFile): CallHistoryEntry? =
        mapToMessageData(file, credentialsManager)?.let { toEntry(it) }

    private suspend fun toEntry(msg: MessageUiModel): CallHistoryEntry? {
        if (msg.isDeleted) return null
        val content = msg.messageContent as? MessageContent.CallEvent ?: return null
        val descriptor = content.descriptor ?: run {
            if (unparseableLogged.add(msg.id)) Logger.w(tag = TAG) { "Skipping call event ${msg.id} with unparseable descriptor" }
            return null
        }
        val self = credentialsManager.getActiveDomain() ?: return null
        val isOwn = msg.originalAuthor == self
        val peer = peerOf(msg.conversationId, isOwn, msg.originalAuthor, self) ?: return null
        val visual = callEventVisual(descriptor, isOwn)
        return CallHistoryEntry(
            messageId = msg.id,
            fileId = msg.fileId,
            conversationId = msg.conversationId,
            peer = peer,
            direction = visual.direction,
            outcome = descriptor.outcome,
            missed = visual.missed,
            video = descriptor.video,
            durationSeconds = descriptor.durationSeconds,
            at = msg.userDate,
        )
    }

    private fun peerOf(conversationId: Uuid, isOwn: Boolean, author: OdinId?, self: OdinId): OdinId? {
        val convo = conversations.getConversationById(conversationId)
        if (convo != null && (convo.isGroupConversation || convo.isWithSelf)) return null
        if (!isOwn) return author
        if (convo == null) {
            Logger.d(tag = TAG) { "Conversation $conversationId not loaded yet; outgoing call skipped until it is" }
            return null
        }
        return convo.participants.firstOrNull { it != self }
    }

    private fun publish() {
        _entries.value = byMessageId.values.sortedWith(
            compareByDescending<CallHistoryEntry> { it.at }.thenBy { it.messageId },
        )
    }

    private inline fun runCatchingNonCancel(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(t, tag = TAG) { "Call history refresh failed" }
        }
    }

    companion object {
        const val PAGE_SIZE = 100
        private const val TAG = "CallHistory"
    }
}
