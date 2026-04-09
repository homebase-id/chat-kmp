package id.homebase.chat.services

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

data class LocalVideoContext(
    val thumbnailBytes: ByteArray,
    val localFilePath: String,
)

class LocalVideoContextStore {
    private val _contexts = MutableStateFlow<Map<Uuid, LocalVideoContext>>(emptyMap())

    fun put(messageId: Uuid, context: LocalVideoContext) {
        _contexts.update { it + (messageId to context) }
    }

    fun get(messageId: Uuid): LocalVideoContext? = _contexts.value[messageId]

    fun observe(messageId: Uuid): Flow<LocalVideoContext?> =
        _contexts.map { it[messageId] }.distinctUntilChanged()

    fun remove(messageId: Uuid) {
        _contexts.update { it - messageId }
    }
}
