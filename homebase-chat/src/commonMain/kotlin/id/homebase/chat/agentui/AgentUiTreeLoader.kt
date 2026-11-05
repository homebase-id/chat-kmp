package id.homebase.chat.agentui

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Decoded trees of cards whose tree rides in the `agent_ui` payload. The bytes stay in the drive
 * cache; this keeps the decoded result in memory so a card scrolled back into view has its full
 * height on the first frame. Loads are started when a message is surfaced, not when it is composed.
 */
class AgentUiTreeLoader(
    private val fetch: suspend (fileId: Uuid, keyHeader: KeyHeader, lastModified: Long?) -> ByteArray?,
    private val scope: CoroutineScope,
    events: EventBus? = null,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init {
        events?.let { bus -> scope.launch { bus.events.collect { if (it is BackendEvent.SessionEnded) clear() } } }
    }

    data class Key(val fileId: Uuid, val lastModified: Long?, val iv: String, val imageKeys: Set<String>)

    private class Request(val key: Key, val messageId: Uuid, val keyHeader: KeyHeader)

    private val trees = MutableStateFlow<Map<Key, AgentUiTreeResult>>(emptyMap())
    private val mutex = Mutex()
    private val pending = HashMap<Key, Deferred<AgentUiTreeResult?>>()

    /** The decoded tree if it is already in memory; never suspends, so a composable can read it on its first frame. */
    fun cached(message: MessageUiModel): AgentUiTreeResult? = request(message)?.let { trees.value[it.key] }

    /** Null when the payload could not be fetched (worth retrying later); an invalid tree is a result, not a failure. */
    suspend fun load(message: MessageUiModel): AgentUiTreeResult? {
        val req = request(message) ?: return null
        trees.value[req.key]?.let { return it }
        val job = mutex.withLock { pending.getOrPut(req.key) { scope.async { fetchAndDecode(req) } } }
        return job.await()
    }

    fun warm(messages: List<MessageUiModel>) {
        for (m in messages) {
            val req = request(m) ?: continue
            if (req.key !in trees.value) scope.launch { load(m) }
        }
    }

    /** Drops every decoded tree; called when the session ends so one identity's cards never outlive it. */
    fun clear() {
        trees.value = emptyMap()
    }

    private suspend fun fetchAndDecode(req: Request): AgentUiTreeResult? = try {
        val bytes = try {
            fetch(req.key.fileId, req.keyHeader, req.key.lastModified)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG) { "tree fetch failed id=${req.messageId}: ${e.message}" }
            null
        }
        bytes?.let {
            val result = withContext(decodeDispatcher) { AgentUiTree.decode(it, req.key.imageKeys) }
            trees.update { m -> (m + (req.key to result)).let { if (it.size > MAX_TREES) it.entries.drop(it.size - MAX_TREES).associate { e -> e.key to e.value } else it } }
            Logger.d(tag = TAG) { "ready id=${req.messageId} source=payload bytes=${it.size} state=${result::class.simpleName}" }
            result
        }
    } finally {
        withContext(NonCancellable) { mutex.withLock { pending.remove(req.key) } }
    }

    private fun request(message: MessageUiModel): Request? {
        if (message.isDeleted) return null
        val content = message.messageContent as? MessageContent.AgentUi ?: return null
        if (content.descriptor == null || content.inlineTree != null) return null
        val payload = message.agentUiTreePayload() ?: return null
        val iv = payload.iv?.bytes ?: return null
        return Request(
            Key(message.fileId, payload.lastModified, Base64.encode(iv), agentUiImageKeys(message.payloads)),
            message.id,
            KeyHeader(iv, message.keyHeader.aesKey),
        )
    }

    private companion object {
        const val TAG = "AgentUi"
        const val MAX_TREES = 200
    }
}

internal fun MessageUiModel.agentUiTreePayload() = payloads?.firstOrNull { it.key == ChatProtocol.PAYLOAD_KEY_AGENT_UI }
