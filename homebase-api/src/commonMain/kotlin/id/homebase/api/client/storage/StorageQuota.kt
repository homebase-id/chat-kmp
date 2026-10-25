package id.homebase.api.client.storage

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StorageQuotaState(
    val usage: StorageUsage? = null,
    /** A payload upload was refused with 507, or the server says the identity is over its quota. */
    val isFull: Boolean = false,
)

/**
 * The one place the app knows the identity is out of storage. Uploads report a refusal here, and
 * [refresh] asks the server, which also clears the state once enough was deleted.
 */
class StorageQuota(private val provider: StorageUsageProvider) {
    private val _state = MutableStateFlow(StorageQuotaState())
    val state: StateFlow<StorageQuotaState> = _state.asStateFlow()

    fun reportStorageFull() {
        _state.update { it.copy(isFull = true) }
    }

    suspend fun refresh(): StorageUsage? {
        val usage = runCatching { provider.getUsage() }
            .onFailure { Logger.w(tag = TAG) { "storage usage unavailable: ${it.message}" } }
            .getOrElse { return _state.value.usage }
        _state.value = StorageQuotaState(usage = usage, isFull = usage?.isOverQuota == true)
        return usage
    }

    private companion object {
        const val TAG = "StorageQuota"
    }
}
