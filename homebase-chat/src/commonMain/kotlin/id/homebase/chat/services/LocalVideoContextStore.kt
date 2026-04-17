package id.homebase.chat.services

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    ) : LocalAttachmentContext {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Video) return false
            return thumbnailBytes.contentEquals(other.thumbnailBytes) &&
                    localFilePath == other.localFilePath &&
                    aspectRatio == other.aspectRatio
        }
        override fun hashCode(): Int {
            var result = thumbnailBytes.contentHashCode()
            result = 31 * result + localFilePath.hashCode()
            result = 31 * result + (aspectRatio?.hashCode() ?: 0)
            return result
        }
    }

    data class Image(
        override val localFilePath: String,
        override val aspectRatio: Float?,
    ) : LocalAttachmentContext
}

class LocalAttachmentContextStore {
    private val _contexts =
        MutableStateFlow<Map<Uuid, Map<String, LocalAttachmentContext>>>(emptyMap())

    fun put(messageId: Uuid, payloadKey: String, context: LocalAttachmentContext) {
        _contexts.update { current ->
            val existing = current[messageId].orEmpty()
            current + (messageId to (existing + (payloadKey to context)))
        }
    }

    fun get(messageId: Uuid, payloadKey: String): LocalAttachmentContext? =
        _contexts.value[messageId]?.get(payloadKey)

    fun getAll(messageId: Uuid): Map<String, LocalAttachmentContext> =
        _contexts.value[messageId].orEmpty()

    fun hasAny(messageId: Uuid): Boolean =
        _contexts.value[messageId]?.isNotEmpty() == true

    fun observe(messageId: Uuid, payloadKey: String): Flow<LocalAttachmentContext?> =
        _contexts.map { it[messageId]?.get(payloadKey) }.distinctUntilChanged()

    fun observeAll(messageId: Uuid): Flow<Map<String, LocalAttachmentContext>> =
        _contexts.map { it[messageId].orEmpty() }.distinctUntilChanged()

    fun remove(messageId: Uuid) {
        _contexts.update { it - messageId }
    }
}
