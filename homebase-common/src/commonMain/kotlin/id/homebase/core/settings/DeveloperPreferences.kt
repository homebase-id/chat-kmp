package id.homebase.core.settings

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * Flags for features that are built but not yet offered to users — set from the developer menu,
 * which is the only place they appear.
 *
 * Stored in keyValue like every other preference, so they are wiped on logout. That is the right
 * default for a dark launch: signing out returns the app to what a real user would see.
 */
class DeveloperPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    /**
     * Whether the owner can act on a connection review — the Review button, the contact-detail
     * prompt, and "Mark as new".
     *
     * Off by default. The states themselves (New / Chat / Circle, and the marks on circle chips)
     * are not gated: they report what the server already says, and a contact only acquires a
     * pending or waiting circle once someone has used the review anyway.
     */
    private val _connectionReviewEnabled =
        MutableStateFlow(readBoolean(CONNECTION_REVIEW_KEY, default = false))
    val connectionReviewEnabled: StateFlow<Boolean> = _connectionReviewEnabled.asStateFlow()

    suspend fun setConnectionReviewEnabled(value: Boolean) {
        if (_connectionReviewEnabled.value == value) return
        keyValue.upsertValue(CONNECTION_REVIEW_KEY, encode(value))
        _connectionReviewEnabled.value = value
    }

    fun reload() {
        _connectionReviewEnabled.value = readBoolean(CONNECTION_REVIEW_KEY, default = false)
    }

    private fun readBoolean(key: Uuid, default: Boolean): Boolean {
        // Bootstrap-only sync read, seeding the StateFlow at construction — same rationale as
        // LocationPreferences: commonMain has no runBlocking on wasmJs.
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        // 0a08xx — developer flags. Vault owns 0a01xx, Moments 0a02xx, Location 0a03xx,
        // 0a04xx and 0a07xx are taken; 0a08 is the next free namespace.
        val CONNECTION_REVIEW_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0801")
    }
}
