package id.homebase.chat.services

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * In-memory, per-session cache of local preview data for outgoing attachments.
 *
 * Populated at send time so placeholder and optimistic-write bubbles can render
 * without waiting for Coil to load from the server or local encrypted storage.
 * Entries are keyed by (messageId, payloadKey) so each payload in a multi-
 * attachment message gets its own preview.
 *
 * Entries persist for the app session once upload completes, so the bubble
 * keeps showing the crisp local bitmap forever rather than swapping to the
 * progressively-loaded server copy.
 */
sealed interface LocalAttachmentContext {
    val aspectRatio: Float?
    val localFilePath: String

    data class Video(
        val thumbnailBytes: ByteArray,
        override val localFilePath: String,
        override val aspectRatio: Float?,
        /** Trim metadata so local playback clips to the same range that gets uploaded. */
        val trimStartMs: Long? = null,
        val trimEndMs: Long? = null,
        /** Source-file duration; needed to clip when trimEndMs is null but trimStartMs is set. */
        val durationMs: Long? = null,
    ) : LocalAttachmentContext {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Video) return false
            return thumbnailBytes.contentEquals(other.thumbnailBytes) &&
                    localFilePath == other.localFilePath &&
                    aspectRatio == other.aspectRatio &&
                    trimStartMs == other.trimStartMs &&
                    trimEndMs == other.trimEndMs &&
                    durationMs == other.durationMs
        }
        override fun hashCode(): Int {
            var result = thumbnailBytes.contentHashCode()
            result = 31 * result + localFilePath.hashCode()
            result = 31 * result + (aspectRatio?.hashCode() ?: 0)
            result = 31 * result + (trimStartMs?.hashCode() ?: 0)
            result = 31 * result + (trimEndMs?.hashCode() ?: 0)
            result = 31 * result + (durationMs?.hashCode() ?: 0)
            return result
        }
    }

    data class Image(
        override val localFilePath: String,
        override val aspectRatio: Float?,
    ) : LocalAttachmentContext
}

class LocalAttachmentContextStore(
    eventBus: EventBus,
    scope: CoroutineScope,
) {
    private val _contexts =
        MutableStateFlow<Map<Uuid, Map<String, LocalAttachmentContext>>>(emptyMap())

    init {
        // Logout: drop the previous identity's outgoing-attachment previews
        // (local file paths + thumbnail bytes) so they don't survive into the
        // next session.
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.SessionEnded) reset()
            }
        }
    }

    /** Logout: clear all cached attachment previews. */
    fun reset() {
        _contexts.value = emptyMap()
    }

    fun put(messageId: Uuid, payloadKey: String, context: LocalAttachmentContext) {
        _contexts.update { current ->
            val existing = current[messageId].orEmpty()
            current + (messageId to (existing + (payloadKey to context)))
        }
    }

    fun get(messageId: Uuid, payloadKey: String): LocalAttachmentContext? {
        val ctx = _contexts.value[messageId]?.get(payloadKey) ?: return null
        if (!fileExists(ctx.localFilePath)) {
            evictEntry(messageId, payloadKey)
            return null
        }
        return ctx
    }

    fun getAll(messageId: Uuid): Map<String, LocalAttachmentContext> {
        val entries = _contexts.value[messageId] ?: return emptyMap()
        val valid = entries.filterValues { fileExists(it.localFilePath) }
        if (valid.size < entries.size) {
            evictStaleEntries(messageId, entries, valid)
        }
        return valid
    }

    fun hasAny(messageId: Uuid): Boolean =
        _contexts.value[messageId]?.values?.any { fileExists(it.localFilePath) } == true

    fun observe(messageId: Uuid, payloadKey: String): Flow<LocalAttachmentContext?> =
        _contexts.map { map ->
            val ctx = map[messageId]?.get(payloadKey) ?: return@map null
            if (!fileExists(ctx.localFilePath)) null else ctx
        }.distinctUntilChanged()

    fun observeAll(messageId: Uuid): Flow<Map<String, LocalAttachmentContext>> =
        _contexts.map { map ->
            map[messageId]?.filterValues { fileExists(it.localFilePath) }.orEmpty()
        }.distinctUntilChanged()

    fun remove(messageId: Uuid) {
        _contexts.update { it - messageId }
    }

    private fun evictEntry(messageId: Uuid, payloadKey: String) {
        _contexts.update { current ->
            val entries = current[messageId] ?: return@update current
            val updated = entries - payloadKey
            if (updated.isEmpty()) current - messageId else current + (messageId to updated)
        }
    }

    private fun evictStaleEntries(
        messageId: Uuid,
        all: Map<String, LocalAttachmentContext>,
        valid: Map<String, LocalAttachmentContext>,
    ) {
        if (valid.isEmpty()) {
            _contexts.update { it - messageId }
        } else if (valid.size < all.size) {
            _contexts.update { it + (messageId to valid) }
        }
    }
}
